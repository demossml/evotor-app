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
        /** Строк присоединено к подтверждённым карточкам (своя группа/без группы). */
        val attachedVariants: Int = 0,
        /** Строк отброшено: такой объём уже есть в подтверждённой карточке. */
        val droppedDuplicates: Int = 0,
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

    private data class BaseKey(val scope: String?, val base: String)

    /** Объём: из backend (`sizeValue`) или из названия «… 300 мл». */
    private fun sizeOf(p: CatalogProduct): Int? =
        p.sizeMl ?: SIZE_IN_NAME.find(p.name.trim())?.groupValues?.get(2)?.toIntOrNull()

    /** Название без хвоста объёма: «Капучино 300 мл» → «капучино». */
    private fun baseKeyOf(p: CatalogProduct): BaseKey? {
        val base = p.name.replace(SIZE_SUFFIX, "").trim().lowercase()
        if (base.isEmpty()) return null
        val scope = p.groupName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return BaseKey(scope, base)
    }

    /**
     * Плитки главного экрана.
     *
     * Правило: объёмы одной карточки определяются только **подтверждённой** связью
     * (backend `groupId`/`groups[]` или метка `#group=`). Строки, которые ссылаются на
     * ту же карточку лишь названием/объёмом (своя «слабая» группа из одной позиции или
     * её отсутствие), не создают отдельную плитку: они присоединяются к подтверждённой
     * карточке, а при совпадении объёма — отбрасываются как дубль.
     * Совпадение названия само по себе никогда не объединяет товары.
     */
    fun build(products: List<CatalogProduct>): Model {
        val options = Options(
            milk = products.filter { it.optGroup == "milk" },
            syrup = products.filter { it.optGroup == "syrup" },
            sugar = products.filter { it.optGroup == "sugar" },
            extra = products.filter { it.optGroup == "extra" },
        )
        // Добавки, которые участвуют в схемах: скрываем их с витрины по UUID SKU
        // (toppings[].evotorUuid) — это устойчивая связь. Скрывать по совпадению имени
        // нельзя: обычный товар с таким же названием должен остаться в меню.
        // Классификация добавок по категории приходит из CatalogApi.merge (hideFromMenu).
        val modifierUuids = products.asSequence()
            .flatMap { it.toppings.asSequence() }
            .mapNotNull { it.evotorUuid?.takeIf { u -> u.isNotBlank() } }
            .toSet()
        // Плитками не показываются: #opt=, комбо-определения, hideFromMenu, добавки по uuid.
        val drinks = products.filter { p ->
            p.optGroup == null &&
                !p.isComboDefinition &&
                !p.hideFromMenu &&
                p.uuid !in modifierUuids
        }

        val explicit = LinkedHashMap<String, MutableList<CatalogProduct>>()
        val loose = ArrayList<CatalogProduct>()
        for (p in drinks) {
            val key = p.variantGroup
            if (key != null) explicit.getOrPut(key) { ArrayList() }.add(p) else loose.add(p)
        }

        // Внутри одной группы один объём — одна позиция (иначе в карточке два «300 мл»).
        var droppedDuplicates = 0
        for ((key, list) in explicit.entries.toList()) {
            val seen = HashSet<Int>()
            val unique = ArrayList<CatalogProduct>(list.size)
            for (p in list) {
                val size = sizeOf(p)
                if (size != null && !seen.add(size)) {
                    droppedDuplicates++
                    continue
                }
                unique.add(p)
            }
            explicit[key] = unique
        }

        // Подтверждённая карточка = явная группа с 2+ объёмами. Если одно и то же
        // (папка|база) встречается в разных подтверждённых группах — неоднозначно,
        // такие товары не склеиваем.
        val baseIndex = HashMap<BaseKey, MutableSet<String>>()
        for ((key, list) in explicit) {
            if (list.size < 2) continue
            for (p in list) baseKeyOf(p)?.let { baseIndex.getOrPut(it) { HashSet() }.add(key) }
        }
        val confirmed: Map<BaseKey, String> =
            baseIndex.filterValues { it.size == 1 }.mapValues { it.value.first() }

        var attachedVariants = 0
        fun attachToConfirmed(p: CatalogProduct): Boolean {
            val size = sizeOf(p) ?: return false
            val key = baseKeyOf(p)?.let { confirmed[it] } ?: return false
            val target = explicit[key] ?: return false
            if (target.any { sizeOf(it) == size }) {
                // Тот же объём уже есть в карточке: исходная строка — дубль, не плитка.
                droppedDuplicates++
                return true
            }
            target.add(p.copy(variantGroup = key, sizeMl = size))
            attachedVariants++
            return true
        }

        // «Слабые» явные группы (одна позиция) — в подтверждённую карточку по base+size.
        for ((key, list) in explicit.entries.toList()) {
            if (list.size != 1) continue
            if (attachToConfirmed(list.first())) explicit.remove(key)
        }
        // Строки без группы — туда же; иначе остаются самостоятельной плиткой.
        val singles = ArrayList<CatalogProduct>()
        for (p in loose) if (!attachToConfirmed(p)) singles.add(p)

        val variantsByKey = LinkedHashMap<String, List<CatalogProduct>>()
        val tiles = ArrayList<CatalogProduct>(singles.size + explicit.size)
        tiles.addAll(singles)
        for ((key, list) in explicit) {
            val sorted = list.sortedWith(compareBy({ sizeOf(it) ?: Int.MAX_VALUE }, { it.name.lowercase() }))
            variantsByKey[key] = sorted
            val representative = sorted.firstOrNull { it.defaultVariant }
                ?: sorted.minByOrNull { kotlin.math.abs((sizeOf(it) ?: 350) - 350) }
                ?: sorted.first()
            tiles.add(representative.copy(name = groupName(representative)))
        }
        return Model(
            tiles = tiles.sortedBy { it.name.lowercase() },
            variantsByKey = variantsByKey,
            options = options,
            attachedVariants = attachedVariants,
            droppedDuplicates = droppedDuplicates,
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
