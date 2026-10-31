package ru.sixthcup.evotor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.net.CatalogApi
import java.io.File
import java.math.BigDecimal

/**
 * Persist enrichment so after process death offline we keep photos, toppings schemes and upsell.
 */
object CatalogCache {
    private const val FILE_PREFIX = "catalog_enrich_v2"
    private fun fileName(storeUuid: String?): String {
        val s = storeUuid?.trim().orEmpty()
        return if (s.isEmpty()) "$FILE_PREFIX.json" else "$FILE_PREFIX-${s.take(36)}.json"
    }

    fun load(ctx: Context, storeUuid: String? = null): CatalogApi.Payload? {
        val f = File(ctx.applicationContext.filesDir, fileName(storeUuid))
        // migrate: prefer v2, fall back to v1 filename
        val src = when {
            f.isFile && f.length() > 0L -> f
            else -> File(ctx.applicationContext.filesDir, "catalog_enrich_v1.json").takeIf { it.isFile && it.length() > 0L }
        } ?: return null
        return loadFrom(src)
    }

    /** Чтение кэша из конкретного файла (без Android Context) — покрыто юнит-тестом. */
    internal fun loadFrom(src: File): CatalogApi.Payload? {
        return try {
            val root = JSONObject(src.readText())
            val by = root.optJSONObject("byUuid") ?: JSONObject()
            val map = linkedMapOf<String, CatalogApi.Enrich>()
            val keys = by.keys()
            while (keys.hasNext()) {
                val uuid = keys.next()
                val o = by.getJSONObject(uuid)
                fun optStr(k: String): String? =
                    if (!o.has(k) || o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }
                map[uuid] = CatalogApi.Enrich(
                    productId = o.optInt("productId", 0),
                    name = optStr("name"),
                    imageUrl = optStr("imageUrl"),
                    recipe = optStr("recipe"),
                    countsAsCup = if (o.has("countsAsCup") && !o.isNull("countsAsCup")) o.optBoolean("countsAsCup") else null,
                    freeEligible = if (o.has("freeEligible") && !o.isNull("freeEligible")) o.optBoolean("freeEligible") else null,
                    toppings = parseToppings(o.optJSONArray("toppings")),
                    upsell = parseUpsell(o.optJSONArray("upsell")),
                    categoryName = optStr("categoryName"),
                    featured = o.optBoolean("featured", false),
                    description = optStr("description"),
                    isFood = if (o.has("isFood") && !o.isNull("isFood")) o.optBoolean("isFood") else null,
                    hideFromMenu = o.optBoolean("hideFromMenu", false) || o.optBoolean("isGroup", false),
                    isGroup = o.optBoolean("isGroup", false),
                    parentProductId = if (o.has("parentProductId") && !o.isNull("parentProductId")) o.optInt("parentProductId") else null,
                    groupId = if (o.has("groupId") && !o.isNull("groupId")) o.optInt("groupId") else null,
                    variantLabel = optStr("variantLabel"),
                    variantSort = if (o.has("variantSort") && !o.isNull("variantSort")) o.optInt("variantSort") else null,
                    sizeValue = if (o.has("sizeValue") && !o.isNull("sizeValue")) o.optInt("sizeValue") else null,
                    sizeUnit = optStr("sizeUnit"),
                    articleNumber = optStr("articleNumber"),
                    categoryId = if (o.has("categoryId") && !o.isNull("categoryId")) o.optInt("categoryId") else null,
                    // Старый кэш без этих полей: товар считаем продаваемым.
                    sellable = if (o.has("sellable")) o.optBoolean("sellable", true) else true,
                    notReadyReason = optStr("notReadyReason"),
                )
            }
            fun parseEnrich(o: JSONObject): CatalogApi.Enrich {
                fun str(k: String): String? = if (!o.has(k) || o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }
                return CatalogApi.Enrich(
                    productId = o.optInt("productId", 0),
                    name = str("name"),
                    imageUrl = str("imageUrl"),
                    recipe = str("recipe"),
                    countsAsCup = if (o.has("countsAsCup") && !o.isNull("countsAsCup")) o.optBoolean("countsAsCup") else null,
                    freeEligible = if (o.has("freeEligible") && !o.isNull("freeEligible")) o.optBoolean("freeEligible") else null,
                    toppings = parseToppings(o.optJSONArray("toppings")),
                    upsell = parseUpsell(o.optJSONArray("upsell")),
                    categoryName = str("categoryName"),
                    featured = o.optBoolean("featured", false),
                    description = str("description"),
                    isFood = if (o.has("isFood") && !o.isNull("isFood")) o.optBoolean("isFood") else null,
                    hideFromMenu = o.optBoolean("hideFromMenu", false) || o.optBoolean("isGroup", false),
                    isGroup = o.optBoolean("isGroup", false),
                    parentProductId = if (o.has("parentProductId") && !o.isNull("parentProductId")) o.optInt("parentProductId") else null,
                    groupId = if (o.has("groupId") && !o.isNull("groupId")) o.optInt("groupId") else null,
                    variantLabel = str("variantLabel"),
                    variantSort = if (o.has("variantSort") && !o.isNull("variantSort")) o.optInt("variantSort") else null,
                    sizeValue = if (o.has("sizeValue") && !o.isNull("sizeValue")) o.optInt("sizeValue") else null,
                    sizeUnit = str("sizeUnit"),
                    articleNumber = str("articleNumber"),
                    sellable = if (o.has("sellable")) o.optBoolean("sellable", true) else true,
                    notReadyReason = str("notReadyReason"),
                    categoryId = if (o.has("categoryId") && !o.isNull("categoryId")) o.optInt("categoryId") else null,
                )
            }
            val named = linkedMapOf<String, CatalogApi.Enrich>()
            val namedObj = root.optJSONObject("byName")
            if (namedObj != null) {
                val namedKeys = namedObj.keys()
                while (namedKeys.hasNext()) {
                    val key = namedKeys.next()
                    val o = namedObj.getJSONObject(key)
                    named[key] = parseEnrich(o)
                }
            }
            if (map.isEmpty() && named.isEmpty()) return null
            val cats = mutableListOf<String>()
            val arr = root.optJSONArray("categoryNames")
            if (arr != null) for (i in 0 until arr.length()) {
                val n = arr.optString(i)
                if (n.isNotBlank()) cats.add(n)
            }
            val upsellDefault = parseUpsell(root.optJSONArray("upsellDefault"))
            // Производные карты восстанавливаем из сохранённых: иначе после перезапуска offline
            // теряется group-fallback добавок и сопоставление по точному имени с объёмом.
            val exact = LinkedHashMap<String, CatalogApi.Enrich>()
            val groupTops = LinkedHashMap<Int, List<Topping>>()
            val strippedGroups = HashMap<String, MutableSet<Int>>()
            (map.values + named.values).forEach { e ->
                val en = e.name ?: return@forEach
                val ex = Names.normalizeExact(en)
                if (ex.isNotEmpty() && !exact.containsKey(ex)) exact[ex] = e
                val g = e.groupId ?: return@forEach
                if (e.toppings.isNotEmpty() && !groupTops.containsKey(g)) groupTops[g] = e.toppings
                val stripped = Names.normalize(en)
                if (stripped.isNotEmpty()) strippedGroups.getOrPut(stripped) { HashSet() }.add(g)
            }
            val nameGroupId = HashMap<String, Int>()
            strippedGroups.forEach { (k, v) -> if (v.size == 1) nameGroupId[k] = v.first() }
            val byArticle = LinkedHashMap<String, CatalogApi.Enrich>()
            (map.values + named.values).forEach { e ->
                val key = e.articleNumber?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@forEach
                if (!byArticle.containsKey(key)) byArticle[key] = e
            }
            val addonCategoryIds = LinkedHashSet<Int>()
            root.optJSONArray("addonCategoryIds")?.let { a ->
                for (i in 0 until a.length()) {
                    val id = a.optInt(i, 0)
                    if (id > 0) addonCategoryIds.add(id)
                }
            }
            val groupByProductId = LinkedHashMap<Int, Int>()
            root.optJSONObject("groupByProduct")?.let { obj ->
                val k = obj.keys()
                while (k.hasNext()) {
                    val key = k.next()
                    val pid = key.toIntOrNull() ?: continue
                    val gid = obj.optInt(key, 0)
                    if (pid > 0 && gid > 0) groupByProductId[pid] = gid
                }
            }
            val groupByUuid = LinkedHashMap<String, Int>()
            root.optJSONObject("groupByUuid")?.let { obj ->
                val k = obj.keys()
                while (k.hasNext()) {
                    val uuid = k.next()
                    val gid = obj.optInt(uuid, 0)
                    if (uuid.isNotBlank() && gid > 0) groupByUuid[uuid] = gid
                }
            }
            val menu = root.optJSONObject("menu")?.let { m ->
                CatalogApi.Menu(
                    showAllTab = m.optBoolean("showAllTab", true),
                    order = CatalogApi.parseNameList(m.optJSONArray("order")),
                    hidden = CatalogApi.parseNameList(m.optJSONArray("hidden")),
                )
            }
            CatalogApi.Payload(
                byUuid = map,
                upsellDefault = upsellDefault,
                categoryNames = cats,
                byName = named,
                byNameExact = exact,
                byGroupId = groupTops,
                nameGroupId = nameGroupId,
                storeUuid = null,
                byArticle = byArticle,
                addonCategoryIds = addonCategoryIds,
                groupByProductId = groupByProductId,
                groupByUuid = groupByUuid,
                menu = menu,
                features = root.optJSONObject("features")?.let { f ->
                    CatalogApi.Features(serverCatalog = f.optBoolean("serverCatalog", false))
                },
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** Время последнего успешного сохранения каталога (0 — кэша нет). Для плашки «данные от HH:MM». */
    fun savedAt(ctx: Context, storeUuid: String? = null): Long {
        return try {
            val f = File(ctx.applicationContext.filesDir, fileName(storeUuid))
            val src = if (f.isFile && f.length() > 0L) f
            else File(ctx.applicationContext.filesDir, "catalog_enrich_v1.json").takeIf { it.isFile && it.length() > 0L }
            src?.let { JSONObject(it.readText()).optLong("savedAt", 0L) } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    fun save(ctx: Context, payload: CatalogApi.Payload, storeUuid: String? = null) {
        if (payload.byUuid.isEmpty() && payload.byName.isEmpty()) return
        saveTo(File(ctx.applicationContext.filesDir, fileName(storeUuid ?: payload.storeUuid)), payload)
    }

    /** Запись кэша в конкретный файл (без Android Context) — покрыто юнит-тестом. */
    internal fun saveTo(file: File, payload: CatalogApi.Payload) {
        if (payload.byUuid.isEmpty() && payload.byName.isEmpty()) return
        try {
            fun enrichJson(e: CatalogApi.Enrich): JSONObject {
                val o = JSONObject()
                    .put("productId", e.productId)
                    .put("name", e.name ?: JSONObject.NULL)
                    .put("imageUrl", e.imageUrl ?: JSONObject.NULL)
                    .put("recipe", e.recipe ?: JSONObject.NULL)
                    .put("categoryName", e.categoryName ?: JSONObject.NULL)
                    .put("featured", e.featured)
                    .put("description", e.description ?: JSONObject.NULL)
                    .put("toppings", toppingsJson(e.toppings))
                    .put("upsell", upsellJson(e.upsell))
                    .put("hideFromMenu", e.hideFromMenu)
                    .put("isGroup", e.isGroup)
                    .put("parentProductId", e.parentProductId ?: JSONObject.NULL)
                    .put("groupId", e.groupId ?: JSONObject.NULL)
                    .put("variantLabel", e.variantLabel ?: JSONObject.NULL)
                    .put("variantSort", e.variantSort ?: JSONObject.NULL)
                    .put("sizeValue", e.sizeValue ?: JSONObject.NULL)
                    .put("sizeUnit", e.sizeUnit ?: JSONObject.NULL)
                    .put("articleNumber", e.articleNumber ?: JSONObject.NULL)
                    .put("categoryId", e.categoryId ?: JSONObject.NULL)
                    .put("sellable", e.sellable)
                    .put("notReadyReason", e.notReadyReason ?: JSONObject.NULL)
                if (e.countsAsCup != null) o.put("countsAsCup", e.countsAsCup)
                if (e.freeEligible != null) o.put("freeEligible", e.freeEligible)
                if (e.isFood != null) o.put("isFood", e.isFood)
                return o
            }
            val by = JSONObject()
            for ((uuid, e) in payload.byUuid) by.put(uuid, enrichJson(e))
            val byName = JSONObject()
            for ((name, e) in payload.byName) byName.put(name, enrichJson(e))
            val cats = JSONArray()
            payload.categoryNames.forEach { cats.put(it) }
            val addonCats = JSONArray()
            payload.addonCategoryIds.forEach { addonCats.put(it) }
            val groupByProduct = JSONObject()
            payload.groupByProductId.forEach { (pid, gid) -> groupByProduct.put(pid.toString(), gid) }
            val groupByUuid = JSONObject()
            payload.groupByUuid.forEach { (uuid, gid) -> groupByUuid.put(uuid, gid) }
            val root = JSONObject()
                .put("byUuid", by)
                .put("byName", byName)
                .put("categoryNames", cats)
                .put("upsellDefault", upsellJson(payload.upsellDefault))
                .put("addonCategoryIds", addonCats)
                .put("groupByProduct", groupByProduct)
                .put("groupByUuid", groupByUuid)
                .put("savedAt", System.currentTimeMillis())
                .put("features", JSONObject().put("serverCatalog", payload.features?.serverCatalog == true))
            // Настройка вкладок переживает offline-запуск так же, как остальной каталог.
            payload.menu?.let { m ->
                val order = JSONArray()
                m.order.forEach { order.put(it) }
                val hidden = JSONArray()
                m.hidden.forEach { hidden.put(it) }
                root.put(
                    "menu",
                    JSONObject().put("showAllTab", m.showAllTab).put("order", order).put("hidden", hidden),
                )
            }
            file.writeText(root.toString())
        } catch (_: Throwable) {
        }
    }

    private fun toppingsJson(list: List<Topping>): JSONArray = ToppingJson.toJson(list)

    private fun upsellJson(list: List<UpsellItem>): JSONArray {
        val a = JSONArray()
        list.forEach { u ->
            a.put(
                JSONObject()
                    .put("productId", u.productId)
                    .put("evotorUuid", u.evotorUuid ?: JSONObject.NULL)
                    .put("name", u.name)
                    .put("priceRub", u.priceRub.toPlainString())
                    .put("imageUrl", u.imageUrl ?: JSONObject.NULL),
            )
        }
        return a
    }

    private fun parseToppings(a: JSONArray?): List<Topping> = ToppingJson.parse(a)

    private fun parseUpsell(a: JSONArray?): List<UpsellItem> {
        if (a == null) return emptyList()
        val out = ArrayList<UpsellItem>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(
                UpsellItem(
                    productId = o.optInt("productId"),
                    evotorUuid = o.optString("evotorUuid").ifBlank { null },
                    name = o.optString("name"),
                    priceRub = try { BigDecimal(o.optString("priceRub", "0")) } catch (_: Throwable) { BigDecimal.ZERO },
                    imageUrl = o.optString("imageUrl").ifBlank { null },
                ),
            )
        }
        return out
    }
}
