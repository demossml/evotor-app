package ru.sixthcup.evotor.net

import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.FoodRules
import ru.sixthcup.evotor.data.ProductMarkers
import ru.sixthcup.evotor.data.Topping
import ru.sixthcup.evotor.data.UpsellItem
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log

/**
 * Каталог 6.7 (топинги, фото, upsell, категории, фокус) через HTTPS/прокси Эвотор.
 * Секретов в APK нет. Вызывать с фона.
 *
 * Категории и «Фокус» берутся с backend, если в номенклатуре Эвотор нет parent-групп
 * или меток #focus в DESCRIPTION (типичный случай после push из админки).
 */
object CatalogApi {
    data class Payload(
        val byUuid: Map<String, Enrich>,
        val upsellDefault: List<UpsellItem>,
        /** Имена категорий из 6.7 (для чипов, если Evotor groups пустые). */
        val categoryNames: List<String> = emptyList(),
    )

    data class Enrich(
        val productId: Int,
        val imageUrl: String?,
        val recipe: String?,
        val countsAsCup: Boolean?,
        val freeEligible: Boolean?,
        val toppings: List<Topping>,
        val upsell: List<UpsellItem>,
        val categoryName: String? = null,
        val featured: Boolean = false,
        val description: String? = null,
        /** Явный флаг еды с backend (`isFood`), если пришёл. */
        val isFood: Boolean? = null,
        /** Backend product group and variant metadata from the 6.7 admin. */
        val groupId: Int? = null,
        val variantLabel: String? = null,
        val variantSort: Int? = null,
        val sizeValue: Int? = null,
        val sizeUnit: String? = null,
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
            // No Authorization in APK: Evotor Cloud injects it when installed from cabinet.
            // Backend allows catalog without token unless EVOTOR_PROXY_ENFORCE=1.
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (status !in 200..299) {
                Log.w("CatalogApi", "catalog HTTP $status len=${body.length}")
                return Payload(emptyMap(), emptyList())
            }
            val parsed = parse(body)
            val withPhoto = parsed.byUuid.values.count { !it.imageUrl.isNullOrBlank() }
            Log.i("CatalogApi", "catalog ok products=${parsed.byUuid.size} withImageUrl=$withPhoto")
            parsed
        } catch (e: Throwable) {
            Log.e("CatalogApi", "catalog fetch failed", e)
            Payload(emptyMap(), emptyList())
        }
    }

    fun merge(products: List<CatalogProduct>, payload: Payload): List<CatalogProduct> {
        if (payload.byUuid.isEmpty() && payload.categoryNames.isEmpty()) return products
        val merged = products.map { p ->
            val e = payload.byUuid[p.uuid] ?: return@map p
            val featured = p.featured || e.featured ||
                ProductMarkers.isFocus(e.description) ||
                ProductMarkers.isCombo(p.name, e.description)
            val tops = e.toppings.ifEmpty { p.toppings }
            p.copy(
                imageUrl = e.imageUrl ?: p.imageUrl,
                recipe = e.recipe?.takeIf { it.isNotBlank() } ?: p.recipe,
                toppings = tops,
                upsell = e.upsell.ifEmpty { p.upsell },
                countsAsCupServer = e.countsAsCup,
                freeEligible = e.freeEligible ?: p.freeEligible,
                // Если в Эвотор товар без parent-группы — подставляем категорию 6.7.
                groupName = p.groupName?.takeIf { it.isNotBlank() } ?: e.categoryName,
                isFood = FoodRules.isFood(p.name, p.groupName, e.categoryName, e.description ?: p.description, e.isFood),
                featured = featured,
                // Схема добавок с backend = можно открывать item-sheet с опциями.
                supportsMods = p.supportsMods || tops.isNotEmpty(),
                // Admin-managed product groups/variants are authoritative; do not require
                // staff to maintain #group/#size markers manually in Evotor descriptions.
                variantGroup = e.groupId?.let { "admin-group:$it" } ?: p.variantGroup,
                sizeMl = e.sizeValue?.takeIf { e.sizeUnit.equals("ml", true) || e.sizeUnit.isNullOrBlank() } ?: p.sizeMl,
                optLabel = e.variantLabel?.takeIf { it.isNotBlank() } ?: p.optLabel,
            )
        }
        return applySoftFocus(merged)
    }

    /**
     * Если ни у одного товара нет featured — поднимаем в «Фокус» до 6 позиций с фото
     * (или первые 6 напитков freeEligible), чтобы блок не пропадал после синка.
     */
    fun applySoftFocus(list: List<CatalogProduct>): List<CatalogProduct> {
        if (list.isEmpty() || list.any { it.featured }) return list
        val preferred = list.filter { !it.imageUrl.isNullOrBlank() }
            .ifEmpty { list.filter { it.freeEligible } }
            .ifEmpty { list }
            .take(6)
            .map { it.uuid }
            .toSet()
        if (preferred.isEmpty()) return list
        return list.map { if (it.uuid in preferred) it.copy(featured = true) else it }
    }

    private fun parse(body: String): Payload {
        val root = JSONObject(body)
        val arr = root.optJSONArray("products") ?: JSONArray()
        val map = HashMap<String, Enrich>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val uuid = o.optString("evotorUuid").trim()
            if (uuid.isEmpty()) continue
            val recipe = o.optString("recipeText").ifBlank { o.optString("recipe") }.ifBlank { null }
            val desc = o.optString("description").ifBlank { null }
            val featured = o.optBoolean("featured", false) ||
                ProductMarkers.isFocus(desc) ||
                ProductMarkers.isCombo(o.optString("name"), desc)
            map[uuid] = Enrich(
                productId = if (o.has("productId")) o.optInt("productId") else o.optInt("id"),
                imageUrl = o.optString("imageUrl").ifBlank { null },
                recipe = recipe,
                countsAsCup = if (o.has("countsAsCup")) o.optBoolean("countsAsCup") else null,
                freeEligible = if (o.has("freeEligible")) o.optBoolean("freeEligible") else null,
                toppings = parseToppings(o.optJSONArray("toppings")),
                upsell = parseUpsell(o.optJSONArray("upsell")),
                categoryName = o.optString("categoryName").ifBlank { null },
                featured = featured,
                description = desc,
                isFood = if (o.has("isFood") && !o.isNull("isFood")) o.optBoolean("isFood") else null,
                groupId = if (o.has("groupId") && !o.isNull("groupId")) o.optInt("groupId") else null,
                variantLabel = o.optString("variantLabel").ifBlank { null },
                variantSort = if (o.has("variantSort")) o.optInt("variantSort") else null,
                sizeValue = if (o.has("sizeValue") && !o.isNull("sizeValue")) o.optInt("sizeValue") else null,
                sizeUnit = o.optString("sizeUnit").ifBlank { null },
            )
        }
        val catNames = ArrayList<String>()
        val cats = root.optJSONArray("categories")
        if (cats != null) {
            for (i in 0 until cats.length()) {
                val n = cats.getJSONObject(i).optString("name").trim()
                if (n.isNotEmpty()) catNames.add(n)
            }
        }
        // Fallback: unique categoryName from products order
        if (catNames.isEmpty()) {
            val seen = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i).optString("categoryName").trim()
                if (n.isNotEmpty()) seen.add(n)
            }
            catNames.addAll(seen)
        }
        val upsellDefault = parseUpsell(root.optJSONArray("upsellDefault"))
        return Payload(map, upsellDefault, catNames)
    }

    private fun parseToppings(a: JSONArray?): List<Topping> {
        if (a == null) return emptyList()
        val out = ArrayList<Topping>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val price = when {
                o.has("priceRub") -> o.optDouble("priceRub", 0.0)
                else -> o.optDouble("price", 0.0)
            }
            out.add(
                Topping(
                    id = o.optInt("id"),
                    name = o.optString("name"),
                    priceRub = BigDecimal.valueOf(price),
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
            val price = when {
                o.has("priceRub") -> o.optDouble("priceRub", 0.0)
                else -> o.optDouble("price", 0.0)
            }
            out.add(
                UpsellItem(
                    productId = if (o.has("productId")) o.optInt("productId") else o.optInt("id"),
                    evotorUuid = o.optString("evotorUuid").ifBlank { null },
                    name = o.optString("name"),
                    priceRub = BigDecimal.valueOf(price),
                    imageUrl = o.optString("imageUrl").ifBlank { null },
                )
            )
        }
        return out
    }
}
