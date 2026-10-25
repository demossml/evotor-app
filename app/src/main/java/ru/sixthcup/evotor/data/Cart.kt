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
    /** Группа комбо: позиции с одним id продаются как один набор со скидкой. None — обычная позиция. */
    val comboGroupId: String? = null,
    /** Какой комбо-набор (`Combos.id`) описывает группу. */
    val comboId: String? = null,
    /**
     * `Topping.id`, если строка — платная добавка из схемы каталога.
     * Нужен, чтобы перед «В чек» сверить опцию с последним каталогом (наличие, available, evotorUuid).
     */
    val optionId: Int? = null,
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
    private const val KEY_LAST = "last"
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
    /** Количество по набору uuid (плитка с несколькими размерами = сумма по всем вариантам). */
    fun qtyAny(uuids: Collection<String>): Int = lines.values.filter { it.productUuid in uuids }.sumOf { it.quantity.toInt() }

    /** Убрать одну единицу последней добавленной позиции из набора uuid (любой размер/опции). */
    fun decAny(uuids: Collection<String>) {
        val entry = lines.entries.lastOrNull { it.value.productUuid in uuids } ?: return
        val q = entry.value.quantity
        if (q <= BigDecimal.ONE) lines.remove(entry.key)
        else lines[entry.key] = entry.value.copy(quantity = q.subtract(BigDecimal.ONE))
        save()
    }

    fun count(): Int = lines.values.sumOf { it.quantity.toInt() }

    fun clear() { lines.clear(); save() }

    /** Запомнить текущий чек как «прошлый» — для кнопки «Повторить» на пустом чеке. */
    fun rememberAsLast() {
        val c = ctx ?: return
        if (lines.isEmpty()) return
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST, encode(lines.values.toList()).toString()).apply()
        } catch (_: Throwable) {
        }
    }

    /** Есть ли что повторять. */
    fun hasLastOrder(): Boolean = ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        ?.getString(KEY_LAST, null)?.isNotBlank() == true

    /** Восстановить прошлый чек в пустую корзину. Возвращает false, если повторять нечего. */
    fun repeatLast(): Boolean {
        val c = ctx ?: return false
        if (!lines.isEmpty()) return false
        val raw = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LAST, null) ?: return false
        return try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val line = decode(arr.getJSONObject(i)) ?: continue
                if (!isSellable(line.productUuid)) continue
                val key = line.cartKey.ifBlank { line.productUuid }
                lines[key] = line.copy(cartKey = key)
            }
            save()
            !lines.isEmpty()
        } catch (_: Throwable) {
            lines.clear()
            false
        }
    }

    private fun encode(list: List<CartLine>): JSONArray = JSONArray().apply {
        list.forEach { l ->
            put(JSONObject().put("u", l.productUuid).put("k", l.cartKey.ifBlank { l.productUuid })
                .put("n", l.name).put("p", l.priceRub.toPlainString())
                .put("q", l.quantity.toPlainString()).put("r", l.recipe ?: JSONObject.NULL).put("f", l.freeEligible)
                .put("cg", l.comboGroupId ?: JSONObject.NULL).put("ci", l.comboId ?: JSONObject.NULL)
                .put("oi", l.optionId ?: JSONObject.NULL))
        }
    }

    private fun decode(o: JSONObject): CartLine? = try {
        CartLine(
            productUuid = o.getString("u"), name = o.getString("n"), priceRub = BigDecimal(o.getString("p")),
            quantity = BigDecimal(o.getString("q")), recipe = if (o.isNull("r")) null else o.getString("r"),
            freeEligible = o.optBoolean("f"), cartKey = o.optString("k", o.getString("u")),
            comboGroupId = if (o.isNull("cg")) null else o.getString("cg"),
            comboId = if (o.isNull("ci")) null else o.getString("ci"),
            optionId = if (o.isNull("oi")) null else o.optInt("oi").takeIf { it > 0 },
        )
    } catch (_: Throwable) { null }

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

    /** Удалить всю группу комбо (напиток + выпечка) одним действием. */
    fun removeComboGroup(groupId: String) {
        lines.entries.filter { it.value.comboGroupId == groupId }.map { it.key }.forEach { lines.remove(it) }
        save()
    }

    /** Позиции одной комбо-группы. */
    fun comboLines(groupId: String): List<CartLine> = lines.values.filter { it.comboGroupId == groupId }

    /** Remove one unit of a catalog product (any cartKey variant). */
    fun decProduct(productUuid: String) {
        val entry = lines.entries.lastOrNull { it.value.productUuid == productUuid } ?: return
        val q = entry.value.quantity
        if (q <= BigDecimal.ONE) lines.remove(entry.key)
        else lines[entry.key] = entry.value.copy(quantity = q.subtract(BigDecimal.ONE))
        save()
    }

        fun gross(): BigDecimal =
        lines.values.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.lineTotal()) }

    private fun save() {
        val c = ctx ?: return
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY, encode(lines.values.toList()).toString())
                .putLong(KEY_AT, System.currentTimeMillis()).apply()
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
                val line = decode(arr.getJSONObject(i)) ?: continue
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
