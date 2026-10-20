package ru.sixthcup.evotor.data

import android.content.Context
import android.database.Cursor
import ru.evotor.framework.inventory.InventoryApi
import ru.evotor.framework.inventory.ProductItem
import ru.evotor.framework.inventory.ProductTable
import ru.sixthcup.evotor.BuildConfig
import java.math.BigDecimal

data class CatalogProduct(
    val uuid: String,
    val name: String,
    /** Цена в рублях (то, что видит бариста и что уходит в Position). */
    val priceRub: BigDecimal,
    val allowToSell: Boolean,
    val recipe: String?,
    val description: String?,
    val article: String?,
    val freeEligible: Boolean = false,
    val parentUuid: String? = null,
    val groupName: String? = null,
    /** Товар в блоке «Фокус» (#focus в описании или комбо в названии). */
    val featured: Boolean = false,
    val isCombo: Boolean = false,
    /** «Старая цена» из метки `#old=` (только подпись; в чек уходит priceRub). */
    val oldPriceRub: BigDecimal? = null,
    /** Данные с backend /api/devices/catalog (после merge). */
    val imageUrl: String? = null,
    val toppings: List<Topping> = emptyList(),
    val upsell: List<UpsellItem> = emptyList(),
    val countsAsCupServer: Boolean? = null,
    /** `#group=` — варианты одного напитка (размеры) собираются в одну плитку. */
    val variantGroup: String? = null,
    /** `#size=` — объём варианта в мл. */
    val sizeMl: Int? = null,
    /** `#opt=` — товар-опция: milk | syrup | sugar | extra. */
    val optGroup: String? = null,
    /** `#optlabel=` — подпись опции, если отличается от названия товара. */
    val optLabel: String? = null,
    /** `#default` — вариант, который показывается на плитке. */
    val defaultVariant: Boolean = false,
    /** Есть ли у позиции выбор опций (объём/молоко/сироп). Для выпечки — false. */
    val supportsMods: Boolean = false,
    /** `#drink=` — uuid напитка в составе комбо. */
    val comboDrinkUuid: String? = null,
    /** `#food=` — uuid выпечки в составе комбо. */
    val comboFoodUuid: String? = null,
    /** Картинка второй позиции комбо (выпечка) для плитки «напиток + выпечка». */
    val imageUrl2: String? = null,
    /** Еда (выпечка/десерт) — по [FoodRules]; нужно для решения «показывать ли upsell». */
    val isFood: Boolean = false,
) {
    /** Товар-определение комбо: продаётся не сам, а набором из двух позиций со скидкой. */
    val isComboDefinition: Boolean get() = comboDrinkUuid != null && comboFoodUuid != null

    /** Название для опции: подпись из `#optlabel=` или имя товара. */
    val optionLabel: String get() = optLabel ?: name
}

data class Topping(
    val id: Int,
    val name: String,
    val priceRub: java.math.BigDecimal,
    val groupKey: String,
    val maxCount: Int = 2,
    val evotorUuid: String? = null,
)

data class UpsellItem(
    val productId: Int,
    val evotorUuid: String?,
    val name: String,
    val priceRub: java.math.BigDecimal,
    val imageUrl: String? = null,
)


/**
 * Каталог терминала (ProductTable / InventoryApi, колонки по integration-library v0.6.27).
 * Блокирующий вызов: НЕ вызывать с главного потока (MainActivity грузит каталог в фоне).
 * Один запрос ко всей таблице, без запросов на каждый товар (DESCRIPTION читается из той же строки).
 */
object InventoryRepository {
    fun load(context: Context): List<CatalogProduct> {
        val raw = mutableListOf<CatalogProduct>()
        val groups = HashMap<String, String>()
        val units = Prices.Units.parse(BuildConfig.PRICE_UNITS)
        context.contentResolver.query(ProductTable.URI, null, null, null, null)?.use { c ->
            val uuidCol = col(c, ProductTable.ROW_UUID, "uuid")
            val nameCol = col(c, ProductTable.ROW_NAME, "name")
            val priceCol = col(c, ProductTable.ROW_PRICE_OUT, "priceOut", "PRICE", "price")
            val typeCol = col(c, ProductTable.ROW_TYPE, "type")
            val isGroupCol = col(c, ProductTable.ROW_IS_GROUP)
            val allowCol = col(c, "ALLOW_TO_SELL", "allowToSell")
            val descCol = col(c, ProductTable.ROW_DESCRIPTION, "description")
            val parentCol = col(c, ProductTable.ROW_PARENT_UUID, "parentUuid")
            while (c.moveToNext()) {
                val uuid = uuidCol?.let { c.getString(it) }?.trim().orEmpty()
                val name = nameCol?.let { c.getString(it) }?.trim().orEmpty()
                if (uuid.isEmpty() || name.isEmpty()) continue
                // Группа Эвотор определяется колонкой IS_GROUP (TYPE у групп не «GROUP»).
                val isGroup = (isGroupCol?.let { try { c.getInt(it) > 0 } catch (_: Throwable) { false } } ?: false) ||
                    (typeCol?.let { c.getString(it) }.orEmpty().contains("GROUP", ignoreCase = true))
                if (isGroup) { groups[uuid] = name; continue }
                val price = priceCol?.let { idx ->
                    try { Prices.priceFromEvotorRaw(c.getDouble(idx), units) } catch (_: Throwable) { BigDecimal.ZERO }
                } ?: BigDecimal.ZERO
                val allow = allowCol?.let { c.getInt(it) != 0 } ?: true
                if (!allow) continue
                val desc = descCol?.let { try { c.getString(it) } catch (_: Throwable) { null } }?.trim()?.takeIf { it.isNotEmpty() }
                val parent = parentCol?.let { try { c.getString(it) } catch (_: Throwable) { null } }?.trim()?.takeIf { it.isNotEmpty() }
                raw.add(CatalogProduct(uuid, name, price, allow, desc, desc, null, parentUuid = parent))
            }
        }
        return raw.map { p ->
            val g = p.parentUuid?.let { groups[it] }
            val drink = FreeCupRules.isEligible(p.name, g, p.description)
            p.copy(
                groupName = g,
                freeEligible = drink,
                isFood = FoodRules.isFood(p.name, g, null, p.description),
                // #mods/#nomods из админки перекрывают эвристику «напиток/еда».
                supportsMods = ProductMarkers.modsFlag(p.description) ?: drink,
                featured = ProductMarkers.isFocus(p.description) || ProductMarkers.isCombo(p.name, p.description),
                isCombo = ProductMarkers.isCombo(p.name, p.description),
                oldPriceRub = ProductMarkers.oldPriceRub(p.description),
                variantGroup = ProductMarkers.variantGroup(p.description),
                sizeMl = ProductMarkers.sizeMl(p.description),
                optGroup = ProductMarkers.optGroup(p.description),
                optLabel = ProductMarkers.optLabel(p.description),
                defaultVariant = ProductMarkers.isDefaultVariant(p.description),
                comboDrinkUuid = ProductMarkers.comboRefs(p.description)?.first,
                comboFoodUuid = ProductMarkers.comboRefs(p.description)?.second,
                // Рецепт без служебных меток (#focus/#old=…); description остаётся «сырым» для FreeCupRules.
                recipe = ProductMarkers.cleanDescription(p.description),
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** Рецепт/описание по требованию (тап на «i»), если в строке каталога его не было. */
    fun recipeFor(context: Context, uuid: String): String? = try {
        (InventoryApi.getProductByUuid(context, uuid) as? ProductItem.Product)?.description?.let { ProductMarkers.cleanDescription(it) }
    } catch (_: Throwable) { null }

    private fun col(c: Cursor, vararg names: String): Int? {
        for (n in names) {
            val i = c.getColumnIndex(n)
            if (i >= 0) return i
        }
        return null
    }
}
