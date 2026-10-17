package ru.sixthcup.evotor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

data class CartLine(
    val productUuid: String,
    val name: String,
    val priceRub: BigDecimal,
    val quantity: BigDecimal = BigDecimal.ONE,
    val recipe: String? = null,
    val freeEligible: Boolean = false,
    /** UI/cart identity; differs from productUuid for the same drink with different modifiers. Never sent to Evotor. */
    val cartKey: String = "",
) {
    fun lineTotal(): BigDecimal = priceRub.multiply(quantity)
}

/**
 * Корзина текущего гостя. Копия хранится в SharedPreferences (TTL 30 мин), чтобы заказ не пропадал
 * при возврате с экрана оплаты или перезапуске процесса. Источник истины для цен — терминал Эвотор.
 */
object Cart {
    private const val PREF = "sixthcup_cart"
    private const val KEY = "lines"
    private const val KEY_AT = "at"
    private const val TTL_MS = 30 * 60 * 1000L

    /**
     * Синтетические id добавок, у которых нет товара в номенклатуре Эвотор (backend не прислал evotorUuid).
     * Такая позиция не может попасть в фискальный чек, поэтому в корзину её класть нельзя: иначе гость видит
     * и «платит» сумму, которой в чеке нет.
     */
    const val SYNTHETIC_PREFIX = "mod:"
    fun isSellable(productUuid: String): Boolean = !productUuid.startsWith(SYNTHETIC_PREFIX)

    private val lines = linkedMapOf<String, CartLine>()
    private var ctx: Context? = null

    /** Вызвать один раз при старте: подключает хранилище и восстанавливает свежую корзину. */
    fun attach(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        restore()
    }

    fun all(): List<CartLine> = lines.values.toList()
    fun isEmpty() = lines.isEmpty()
    /** Quantity badge sums configured variants of the same catalog product. */
    fun qty(productUuid: String): Int = lines.values.filter { it.productUuid == productUuid }.sumOf { it.quantity.toInt() }
    fun count(): Int = lines.values.sumOf { it.quantity.toInt() }

    fun clear() { lines.clear(); save() }

    fun add(line: CartLine) {
        if (!isSellable(line.productUuid)) return
        val key = line.cartKey.ifBlank { line.productUuid }
        val normalized = line.copy(cartKey = key)
        val old = lines[key]
        if (old == null) lines[key] = normalized
        else lines[key] = old.copy(quantity = old.quantity.add(line.quantity))
        save()
    }

    /** Remove a specific cart line. Legacy callers may still pass a product UUID. */
    fun remove(cartKeyOrProductUuid: String) {
        if (lines.remove(cartKeyOrProductUuid) == null) {
            val matches = lines.filterValues { it.productUuid == cartKeyOrProductUuid }.keys.toList()
            matches.forEach { lines.remove(it) }
        }
        save()
    }

    /** Update a specific cart line; a product UUID remains supported for legacy single-line carts. */
    fun setQty(cartKeyOrProductUuid: String, qty: BigDecimal) {
        val key = if (lines.containsKey(cartKeyOrProductUuid)) cartKeyOrProductUuid else
            lines.entries.firstOrNull { it.value.productUuid == cartKeyOrProductUuid }?.key ?: return
        val old = lines[key] ?: return
        if (qty <= BigDecimal.ZERO) lines.remove(key)
        else lines[key] = old.copy(quantity = qty)
        save()
    }

    fun gross(): BigDecimal =
        lines.values.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.lineTotal()) }

    private fun save() {
        val c = ctx ?: return
        try {
            val arr = JSONArray()
            lines.values.forEach { l ->
                arr.put(JSONObject().put("u", l.productUuid).put("k", l.cartKey.ifBlank { l.productUuid })
                    .put("n", l.name).put("p", l.priceRub.toPlainString())
                    .put("q", l.quantity.toPlainString()).put("r", l.recipe ?: JSONObject.NULL).put("f", l.freeEligible))
            }
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY, arr.toString()).putLong(KEY_AT, System.currentTimeMillis()).apply()
        } catch (_: Throwable) {
        }
    }

    private fun restore() {
        val c = ctx ?: return
        try {
            val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val s = p.getString(KEY, null) ?: return
            if (System.currentTimeMillis() - p.getLong(KEY_AT, 0L) > TTL_MS) { p.edit().clear().apply(); return }
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val line = CartLine(
                    productUuid = o.getString("u"), name = o.getString("n"), priceRub = BigDecimal(o.getString("p")),
                    quantity = BigDecimal(o.getString("q")), recipe = if (o.isNull("r")) null else o.getString("r"),
                    freeEligible = o.optBoolean("f"), cartKey = o.optString("k", o.getString("u")),
                )
                // Корзины, сохранённые старой версией, могли содержать «mod:…» — в чек они не уходят.
                if (isSellable(line.productUuid)) {
                    val key = line.cartKey.ifBlank { line.productUuid }
                    val old = lines[key]
                    lines[key] = if (old == null) line.copy(cartKey = key) else old.copy(quantity = old.quantity.add(line.quantity))
                }
            }
        } catch (_: Throwable) {
            lines.clear()
        }
    }
}
