package ru.sixthcup.evotor.data

/**
 * Корзина → позиции kitchen-заказа для бариста.
 *
 * Правила:
 *  - основная строка товара = позиция заказа;
 *  - строки-опции (`optionId != null`: платное молоко, сироп, добавка) **не** отдельные
 *    «товары меню» — их читаемые имена уже есть в `recipe` основной строки;
 *  - сколько в имени товара уже написано («Капучино 350 мл»), в модификаторах не повторяем;
 *  - если в корзине остались только строки-опции, показываем их как позиции — заказ не должен
 *    стать пустым.
 *
 * Функция чистая (без Android/сети) — покрыта юнит-тестами.
 */
object KitchenItems {

    data class Line(
        val name: String,
        val qty: Int,
        val mods: List<String> = emptyList(),
        val note: String? = null,
    )

    private const val MOD_SEP = " · "

    fun fromCart(cart: List<CartLine>): List<Line> {
        if (cart.isEmpty()) return emptyList()
        val mains = cart.filter { it.optionId == null && Cart.isSellable(it.productUuid) }
        val rows = mains.ifEmpty { cart.filter { Cart.isSellable(it.productUuid) } }
        return rows.map { line ->
            val name = line.name.trim().ifEmpty { "Позиция" }
            Line(
                name = name,
                qty = qtyOf(line),
                mods = modsOf(line, name),
            )
        }
    }

    private fun qtyOf(line: CartLine): Int =
        line.quantity.toInt().coerceAtLeast(1)

    private fun modsOf(line: CartLine, name: String): List<String> {
        val out = LinkedHashSet<String>()
        val lowerName = name.lowercase()
        line.recipe?.split(MOD_SEP)?.forEach { raw ->
            add(out, raw, lowerName)
        }
        add(out, line.choiceLabel, lowerName)
        return out.toList()
    }

    private fun add(out: MutableSet<String>, raw: String?, lowerName: String) {
        val mod = raw?.trim().orEmpty()
        if (mod.isEmpty()) return
        // Объём и вкус уже в названии товара — не дублируем.
        if (lowerName.contains(mod.lowercase())) return
        out.add(mod.replaceFirstChar { it.titlecase() })
    }
}
