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
        return try {
            val f = File(ctx.applicationContext.filesDir, fileName(storeUuid))
            // migrate: prefer v2, fall back to v1 filename
            val src = when {
                f.isFile && f.length() > 0L -> f
                else -> File(ctx.applicationContext.filesDir, "catalog_enrich_v1.json").takeIf { it.isFile && it.length() > 0L }
            } ?: return null
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
                    hideFromMenu = o.optBoolean("hideFromMenu", false),
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
                    hideFromMenu = o.optBoolean("hideFromMenu", false),
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
            CatalogApi.Payload(map, upsellDefault, cats, named)
        } catch (_: Throwable) {
            null
        }
    }

    fun save(ctx: Context, payload: CatalogApi.Payload, storeUuid: String? = null) {
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
            val root = JSONObject()
                .put("byUuid", by)
                .put("byName", byName)
                .put("categoryNames", cats)
                .put("upsellDefault", upsellJson(payload.upsellDefault))
            File(ctx.applicationContext.filesDir, fileName(storeUuid ?: payload.storeUuid)).writeText(root.toString())
        } catch (_: Throwable) {
        }
    }

    private fun toppingsJson(list: List<Topping>): JSONArray {
        val a = JSONArray()
        list.forEach { t ->
            a.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("priceRub", t.priceRub.toPlainString())
                    .put("groupKey", t.groupKey)
                    .put("maxCount", t.maxCount)
                    .put("evotorUuid", t.evotorUuid ?: JSONObject.NULL)
                    .put("required", t.required)
                    .put("isDefault", t.isDefault)
                    .put("zeroOption", t.zeroOption)
                    .put("available", t.available)
                    .put("productId", t.productId ?: JSONObject.NULL),
            )
        }
        return a
    }

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

    private fun parseToppings(a: JSONArray?): List<Topping> {
        if (a == null) return emptyList()
        val out = ArrayList<Topping>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(
                Topping(
                    id = o.optInt("id"),
                    name = o.optString("name"),
                    priceRub = try { BigDecimal(o.optString("priceRub", "0")) } catch (_: Throwable) { BigDecimal.ZERO },
                    groupKey = o.optString("groupKey", "extra"),
                    maxCount = o.optInt("maxCount", 1).coerceAtLeast(1),
                    evotorUuid = o.optString("evotorUuid").ifBlank { null },
                    required = o.optBoolean("required", false),
                    isDefault = o.optBoolean("isDefault", false),
                    zeroOption = o.optBoolean("zeroOption", false),
                    available = if (o.has("available")) o.optBoolean("available", true) else true,
                    productId = if (o.has("productId") && !o.isNull("productId")) o.optInt("productId") else null,
                ),
            )
        }
        return out
    }

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
