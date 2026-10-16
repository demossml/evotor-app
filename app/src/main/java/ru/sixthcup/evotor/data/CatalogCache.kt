package ru.sixthcup.evotor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.net.CatalogApi
import java.io.File

/**
 * Persist enrichment (imageUrl, toppings meta keys) so after process death offline
 * ThumbLoader can still resolve URLs from disk cache.
 */
object CatalogCache {
    private const val FILE = "catalog_enrich_v1.json"

    fun load(ctx: Context): CatalogApi.Payload? {
        return try {
            val f = File(ctx.applicationContext.filesDir, FILE)
            if (!f.isFile || f.length() == 0L) return null
            val root = JSONObject(f.readText())
            val by = root.optJSONObject("byUuid") ?: return null
            val map = linkedMapOf<String, CatalogApi.Enrich>()
            val keys = by.keys()
            while (keys.hasNext()) {
                val uuid = keys.next()
                val o = by.getJSONObject(uuid)
                fun optStr(k: String): String? =
                    if (!o.has(k) || o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }
                map[uuid] = CatalogApi.Enrich(
                    productId = o.optInt("productId", 0),
                    imageUrl = optStr("imageUrl"),
                    recipe = optStr("recipe"),
                    countsAsCup = if (o.has("countsAsCup") && !o.isNull("countsAsCup")) o.optBoolean("countsAsCup") else null,
                    freeEligible = if (o.has("freeEligible") && !o.isNull("freeEligible")) o.optBoolean("freeEligible") else null,
                    toppings = emptyList(),
                    upsell = emptyList(),
                    categoryName = optStr("categoryName"),
                    featured = o.optBoolean("featured", false),
                    description = optStr("description"),
                )
            }
            if (map.isEmpty()) return null
            val cats = mutableListOf<String>()
            val arr = root.optJSONArray("categoryNames")
            if (arr != null) for (i in 0 until arr.length()) {
                val n = arr.optString(i)
                if (n.isNotBlank()) cats.add(n)
            }
            CatalogApi.Payload(map, emptyList(), cats)
        } catch (_: Throwable) {
            null
        }
    }

    fun save(ctx: Context, payload: CatalogApi.Payload) {
        if (payload.byUuid.isEmpty()) return
        try {
            val by = JSONObject()
            for ((uuid, e) in payload.byUuid) {
                by.put(uuid, JSONObject().apply {
                    put("productId", e.productId)
                    if (e.imageUrl != null) put("imageUrl", e.imageUrl)
                    if (e.recipe != null) put("recipe", e.recipe)
                    if (e.countsAsCup != null) put("countsAsCup", e.countsAsCup)
                    if (e.freeEligible != null) put("freeEligible", e.freeEligible)
                    if (e.categoryName != null) put("categoryName", e.categoryName)
                    put("featured", e.featured)
                    if (e.description != null) put("description", e.description)
                })
            }
            val root = JSONObject()
                .put("byUuid", by)
                .put("categoryNames", JSONArray(payload.categoryNames))
                .put("savedAt", System.currentTimeMillis())
            File(ctx.applicationContext.filesDir, FILE).writeText(root.toString())
        } catch (_: Throwable) {
            // ignore disk errors on cashier
        }
    }
}
