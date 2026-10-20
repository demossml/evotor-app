package ru.sixthcup.evotor.data

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Комбо = реальный напиток + реальная выпечка по цене набора.
 * В чек уходят обе позиции своими uuid; разница уходит скидкой на чек
 * (см. `DiscountIntegrationService`), иначе поедет склад и отчёт по номенклатуре.
 *
 * Определение комбо живёт в товаре Эвотор с метками:
 * ```
 * Комбо Капучино + круассан   390 ₽   #combo #drink=<uuid капучино> #food=<uuid круассана>
 * ```
 * Сам товар-определение не продаётся и не показывается плиткой.
 */
data class Combo(
    val id: String,
    val name: String,
    val desc: String?,
    val bundlePriceRub: BigDecimal,
    val drinkUuid: String,
    val foodUuid: String,
    /** Цена напитка по умолчанию (для расчёта выгоды). */
    val drinkBasePriceRub: BigDecimal,
    val foodPriceRub: BigDecimal,
) {
    /** Фиксированная выгода набора — как `comboDiscountValue` в index. */
    val discountRub: BigDecimal
        get() = drinkBasePriceRub.add(foodPriceRub).subtract(bundlePriceRub)
            .max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN)
}

object Combos {

    /**
     * Собирает комбо из номенклатуры. Напиток ищется среди вариантов его группы —
     * базой считается вариант по умолчанию (или ближайший к 350 мл).
     */
    fun from(products: List<CatalogProduct>): List<Combo> {
        val byUuid = products.associateBy { it.uuid }
        val result = ArrayList<Combo>()
        for (p in products) {
            if (!p.isComboDefinition) continue
            val drinkUuid = p.comboDrinkUuid ?: continue
            val foodUuid = p.comboFoodUuid ?: continue
            val drink = byUuid[drinkUuid] ?: continue
            val food = byUuid[foodUuid] ?: continue
            result.add(
                Combo(
                    id = p.uuid,
                    name = p.name,
                    desc = p.recipe ?: p.description,
                    bundlePriceRub = p.priceRub,
                    drinkUuid = drinkUuid,
                    foodUuid = foodUuid,
                    drinkBasePriceRub = drink.priceRub,
                    foodPriceRub = food.priceRub,
                ),
            )
        }
        return result
    }

    /** Плитка витрины: выглядит как обычная карточка, но открывает combo-sheet. */
    fun tile(combo: Combo, products: List<CatalogProduct>): CatalogProduct {
        val drink = products.firstOrNull { it.uuid == combo.drinkUuid }
        val food = products.firstOrNull { it.uuid == combo.foodUuid }
        return CatalogProduct(
            uuid = combo.id,
            name = combo.name,
            priceRub = combo.bundlePriceRub,
            allowToSell = true,
            recipe = combo.desc,
            description = combo.desc,
            article = null,
            freeEligible = false,
            groupName = drink?.groupName,
            featured = true,
            isCombo = true,
            oldPriceRub = combo.drinkBasePriceRub.add(combo.foodPriceRub).takeIf { it > combo.bundlePriceRub },
            imageUrl = drink?.imageUrl,
            comboDrinkUuid = combo.drinkUuid,
            comboFoodUuid = combo.foodUuid,
            imageUrl2 = food?.imageUrl,
            supportsMods = false,
        )
    }

    /** Сумма выгоды по всем комбо-группам в корзине. */
    fun discountFor(lines: List<CartLine>, combos: List<Combo>): BigDecimal {
        val byId = combos.associateBy { it.id }
        val groups = lines.mapNotNull { it.comboGroupId }.distinct()
        return groups.fold(BigDecimal.ZERO) { acc, gid ->
            val comboId = lines.firstOrNull { it.comboGroupId == gid }?.comboId
            val combo = comboId?.let { byId[it] }
            if (combo == null) acc else acc.add(combo.discountRub)
        }
    }
}
