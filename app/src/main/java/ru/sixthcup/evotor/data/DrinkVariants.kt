package ru.sixthcup.evotor.data

/**
 * Собирает плоский каталог Эвотор в модель витрины: один напиток = одна плитка,
 * а размеры (250/350/450 мл) прячутся внутрь item-sheet — как в дизайне `index`.
 *
 * Источник данных — метки в DESCRIPTION (см. [ProductMarkers]):
 * ```
 * Капучино 250 мл  #group=cappuccino #size=250
 * Капучино 350 мл  #group=cappuccino #size=350 #default
 * Капучино 450 мл  #group=cappuccino #size=450
 * Молоко овсяное   #opt=milk #optlabel=Овсяное
 * ```
 *
 * Цены берутся из Inventory (PRICE_OUT) — дельт на клиенте нет.
 */
object DrinkVariants {

    /** Товары-опции, которые показываются группами внутри item-sheet. */
    data class Options(
        val milk: List<CatalogProduct> = emptyList(),
        val syrup: List<CatalogProduct> = emptyList(),
        val sugar: List<CatalogProduct> = emptyList(),
        val extra: List<CatalogProduct> = emptyList(),
    ) {
        val isEmpty: Boolean get() = milk.isEmpty() && syrup.isEmpty() && sugar.isEmpty() && extra.isEmpty()
    }

    data class Model(
        /** Плитки витрины: один представитель на группу вариантов. */
        val tiles: List<CatalogProduct> = emptyList(),
        /** Ключ плитки → все варианты этой группы по возрастанию объёма. */
        val variantsByKey: Map<String, List<CatalogProduct>> = emptyMap(),
        val options: Options = Options(),
    ) {
        fun variantsOf(tile: CatalogProduct): List<CatalogProduct> =
            variantsByKey[tileKey(tile)].orEmpty().ifEmpty { listOf(tile) }

        /**
         * Есть что выбирать: размеры или опции — но опции только у напитков.
         * Топпинги из `/api/devices/catalog` считаются наравне с товарами `#opt=`:
         * иначе item-sheet не открывался бы там, где бэкенд уже отдал схему добавок.
         */
        fun hasChoice(tile: CatalogProduct): Boolean {
            val available = variantsOf(tile)
            if (available.size > 1) return true
            // Backend toppings on any size variant open the sheet even without #mods /
            // supportsMods (типичный случай: схема добавок только с /api/devices/catalog).
            if (available.any { it.toppings.isNotEmpty() }) return true
            if (!tile.supportsMods) return false
            if (!options.isEmpty) return true
            return false
        }
    }

    fun tileKey(p: CatalogProduct): String = p.variantGroup ?: p.uuid

    private const val AUTO_PREFIX = "auto:"

    /**
     * Объём из названия товара, если метки `#size=` нет: «Капучино 350 мл» → 350.
     * Возвращает ключ авто-группы (по названию без объёма) и объём в мл.
     */
    private fun autoVariant(p: CatalogProduct): Pair<String, Int>? {
        val base = p.name.replace(SIZE_SUFFIX, "").trim()
        val size = p.sizeMl ?: SIZE_IN_NAME.find(p.name.trim())?.groupValues?.get(2)?.toIntOrNull() ?: return null
        if (base.isEmpty() || size <= 0) return null
        // «Капучино» без объёма в названии и без #size — группировать не по чему.
        if (base == p.name.trim() && p.sizeMl == null) return null
        return AUTO_PREFIX + base.lowercase() to size
    }

    fun build(products: List<CatalogProduct>): Model {
        val options = Options(
            milk = products.filter { it.optGroup == "milk" },
            syrup = products.filter { it.optGroup == "syrup" },
            sugar = products.filter { it.optGroup == "sugar" },
            extra = products.filter { it.optGroup == "extra" },
        )
        // Плитками не показываются: товары-опции и определения комбо.
        val drinks = products.filter { it.optGroup == null && !it.isComboDefinition }

        val grouped = LinkedHashMap<String, MutableList<CatalogProduct>>()
        val singles = ArrayList<CatalogProduct>()
        for (p in drinks) {
            // Явная метка #group= приоритетнее; без неё пробуем найти объём в названии
            // («Капучино 350 мл») — тогда размеры работают и без разметки номенклатуры.
            val explicit = p.variantGroup
            val auto = if (explicit == null) autoVariant(p) else null
            val key = explicit ?: auto?.first
            if (key == null) {
                singles.add(p)
            } else {
                val withSize = if (p.sizeMl == null && auto != null) {
                    p.copy(sizeMl = auto.second, variantGroup = key)
                } else {
                    p
                }
                grouped.getOrPut(key) { ArrayList() }.add(withSize)
            }
        }

        val variantsByKey = LinkedHashMap<String, List<CatalogProduct>>()
        val tiles = ArrayList<CatalogProduct>(singles.size + grouped.size)
        for (p in singles) tiles.add(p)
        for ((key, list) in grouped) {
            // Авто-группа из одной позиции — это обычный товар, а не выбор размера.
            if (list.size == 1 && key.startsWith(AUTO_PREFIX)) {
                singles.add(list[0].copy(variantGroup = null))
                continue
            }
            val sorted = list.sortedWith(compareBy({ it.sizeMl ?: Int.MAX_VALUE }, { it.name.lowercase() }))
            variantsByKey[key] = sorted
            val representative = sorted.firstOrNull { it.defaultVariant }
                ?: sorted.minByOrNull { kotlin.math.abs((it.sizeMl ?: 350) - 350) }
                ?: sorted.first()
            tiles.add(representative.copy(name = groupName(representative)))
        }
        return Model(
            tiles = tiles.sortedBy { it.name.lowercase() },
            variantsByKey = variantsByKey,
            options = options,
        )
    }

    /** «Капучино 450 мл» → «Капучино»: на плитке объём не дублируется. */
    fun groupName(p: CatalogProduct): String =
        p.name.replace(SIZE_SUFFIX, "").trim().ifEmpty { p.name }

    fun sizeLabel(sizeMl: Int?): String? = sizeMl?.let { "$it мл" }

    /** Подпись варианта в item-sheet: «350 мл», иначе название товара. */
    fun variantLabel(p: CatalogProduct): String = sizeLabel(p.sizeMl) ?: p.optionLabel

    private val SIZE_SUFFIX = Regex("""\s*\d{2,4}\s*(мл|ml)\.?\s*$""", RegexOption.IGNORE_CASE)

    /** «Капучино 350 мл» → base = «Капучино», size = 350. */
    private val SIZE_IN_NAME = Regex("""^(.*?)\s+(\d{2,4})\s*(мл|ml)\.?\s*$""", RegexOption.IGNORE_CASE)
}
