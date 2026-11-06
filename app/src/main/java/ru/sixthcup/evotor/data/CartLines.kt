package ru.sixthcup.evotor.data

import java.math.BigDecimal

/**
 * Строки корзины для одной порции из окна товара.
 *
 * Единственное место, где выбор кассира превращается в позиции корзины: объём — основная
 * строка, платное молоко и платные добавки — отдельными строками по своему uuid Эвотор.
 * Обычное молоко (zero-option) и опции с `sendToReceipt = false` строк не создают
 * (правила — в [DrinkConfig]).
 */
object CartLines {

    fun of(
        variant: CatalogProduct,
        chosen: List<Topping>,
        recipe: String?,
        signature: String,
        qty: Int = 1,
    ): List<CartLine> {
        val key = signature.ifBlank { variant.uuid }
        val quantity = BigDecimal.valueOf(qty.coerceAtLeast(1).toLong())
        return DrinkConfig.receiptLines(variant, chosen).mapIndexed { index, line ->
            if (index == 0) {
                CartLine(
                    productUuid = line.uuid,
                    name = line.name,
                    priceRub = line.priceRub,
                    quantity = quantity,
                    recipe = recipe,
                    freeEligible = variant.freeEligible,
                    cartKey = key,
                )
            } else {
                // Опция без cartKey: одинаковые добавки у разных напитков дают одну строку с количеством.
                CartLine(
                    productUuid = line.uuid,
                    name = line.name,
                    priceRub = line.priceRub,
                    quantity = quantity,
                    optionId = line.optionId,
                    fromMilkScheme = line.fromMilkScheme,
                )
            }
        }
    }
}
