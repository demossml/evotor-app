package ru.sixthcup.evotor.data

/**
 * Какие добавки показывает item-sheet для выбранного объёма.
 *
 * Схема добавок у размеров одной карточки общая, но на кассе `toppings` могут оказаться только у части размеров
 * (размер не склеился с каталогом, пришёл из номенклатуры Эвотор). Без донора смена объёма на такой размер
 * убирала секции «Молоко»/«Сироп», хотя «Объём» оставался.
 *
 * Донор берётся только из переданного списка вариантов этой же карточки и только если `variantGroup` совпадает:
 * добавки чужой карточки подмешивать нельзя.
 */
object SheetToppings {

    fun forSheet(selected: CatalogProduct, variants: List<CatalogProduct>): List<Topping> {
        if (selected.toppings.isNotEmpty()) return selected.toppings
        return variants
            .firstOrNull { it.uuid != selected.uuid && it.toppings.isNotEmpty() && sameCard(it, selected) }
            ?.toppings
            ?: emptyList()
    }

    /** Выбранный вариант с подставленными добавками (донор или собственные). */
    fun effective(selected: CatalogProduct, variants: List<CatalogProduct>): CatalogProduct {
        val tops = forSheet(selected, variants)
        return if (tops === selected.toppings) selected else selected.copy(toppings = tops)
    }

    private fun sameCard(a: CatalogProduct, b: CatalogProduct): Boolean =
        a.variantGroup == b.variantGroup
}
