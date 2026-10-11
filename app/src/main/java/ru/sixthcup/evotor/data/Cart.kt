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
    fun qty(productUuid: String): Int = lines[productUuid]?.quantity?.toInt() ?: 0
    fun count(): Int = lines.values.sumOf { it.quantity.toInt() }

    fun clear() { lines.clear(); save() }

    fun add(line: CartLine) {
        val old = lines[line.productUuid]
        if (old == null) lines[line.productUuid] = line
        else lines[line.productUuid] = old.copy(quantity = old.quantity.add(line.quantity))
        save()
    }

    fun remove(productUuid: String) {
        lines.remove(productUuid)
        save()
    }

    fun setQty(productUuid: String, qty: BigDecimal) {
        val old = lines[productUuid] ?: return
        if (qty <= BigDecimal.ZERO) lines.remove(productUuid)
        else lines[productUuid] = old.copy(quantity = qty)
        save()
    }

    fun gross(): BigDecimal =
        lines.values.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.lineTotal()) }

    private fun save() {
        val c = ctx ?: return
        try {
            val arr = JSONArray()
            lines.values.forEach { l ->
                arr.put(JSONObject().put("u", l.productUuid).put("n", l.name).put("p", l.priceRub.toPlainString())
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
                    freeEligible = o.optBoolean("f"),
                )
                lines[line.productUuid] = line
            }
        } catch (_: Throwable) {
            lines.clear()
        }
    }
}
