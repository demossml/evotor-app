package ru.sixthcup.evotor.net

import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.Topping
import ru.sixthcup.evotor.data.UpsellItem
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

/**
 * Каталог 6.7 (топинги, фото, upsell) через тот же HTTPS/прокси Эвотор, что и loyalty/resolve.
 * Секретов в APK нет. Вызывать с фона.
 */
object CatalogApi {
    data class Payload(
        val byUuid: Map<String, Enrich>,
        val upsellDefault: List<UpsellItem>,
    )

    data class Enrich(
        val productId: Int,
        val imageUrl: String?,
        val recipe: String?,
        val countsAsCup: Boolean?,
        val freeEligible: Boolean?,
        val toppings: List<Topping>,
        val upsell: List<UpsellItem>,
    )

    fun fetch(storeUuid: String? = null): Payload {
        return try {
            val url = BuildConfig.API_BASE_URL.trimEnd('/') + "/api/devices/catalog"
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            if (!storeUuid.isNullOrBlank()) conn.setRequestProperty("X-Evotor-Store-Uuid", storeUuid)
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (status !in 200..299) return Payload(emptyMap(), emptyList())
            parse(body)
        } catch (_: Throwable) {
            Payload(emptyMap(), emptyList())
        }
    }

    fun merge(products: List<CatalogProduct>, payload: Payload): List<CatalogProduct> {
        if (payload.byUuid.isEmpty()) return products
        return products.map { p ->
            val e = payload.byUuid[p.uuid] ?: return@map p
            p.copy(
                imageUrl = e.imageUrl ?: p.imageUrl,
                recipe = e.recipe?.takeIf { it.isNotBlank() } ?: p.recipe,
                toppings = e.toppings,
                upsell = e.upsell,
                countsAsCupServer = e.countsAsCup,
                freeEligible = e.freeEligible ?: p.freeEligible,
            )
        }
    }

    private fun parse(body: String): Payload {
        val root = JSONObject(body)
        val arr = root.optJSONArray("products") ?: JSONArray()
        val map = HashMap<String, Enrich>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val uuid = o.optString("evotorUuid").trim()
            if (uuid.isEmpty()) continue
            map[uuid] = Enrich(
                productId = o.optInt("productId"),
                imageUrl = o.optString("imageUrl").ifBlank { null },
                recipe = o.optString("recipe").ifBlank { null },
                countsAsCup = if (o.has("countsAsCup")) o.optBoolean("countsAsCup") else null,
                freeEligible = if (o.has("freeEligible")) o.optBoolean("freeEligible") else null,
                toppings = parseToppings(o.optJSONArray("toppings")),
                upsell = parseUpsell(o.optJSONArray("upsell")),
            )
        }
        val upsellDefault = parseUpsell(root.optJSONArray("upsellDefault"))
        return Payload(map, upsellDefault)
    }

    private fun parseToppings(a: JSONArray?): List<Topping> {
        if (a == null) return emptyList()
        val out = ArrayList<Topping>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(
                Topping(
                    id = o.optInt("id"),
                    name = o.optString("name"),
                    priceRub = BigDecimal.valueOf(o.optDouble("priceRub", 0.0)),
                    groupKey = o.optString("groupKey", "extra"),
                    maxCount = o.optInt("maxCount", 2),
                    evotorUuid = o.optString("evotorUuid").ifBlank { null },
                )
            )
        }
        return out
    }

    private fun parseUpsell(a: JSONArray?): List<UpsellItem> {
        if (a == null) return emptyList()
        val out = ArrayList<UpsellItem>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(
                UpsellItem(
                    productId = o.optInt("productId"),
                    evotorUuid = o.optString("evotorUuid").ifBlank { null },
                    name = o.optString("name"),
                    priceRub = BigDecimal.valueOf(o.optDouble("priceRub", 0.0)),
                    imageUrl = o.optString("imageUrl").ifBlank { null },
                )
            )
        }
        return out
    }
}
