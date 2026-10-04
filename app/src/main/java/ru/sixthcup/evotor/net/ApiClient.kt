package ru.sixthcup.evotor.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.data.Category
import ru.sixthcup.evotor.data.Product
import java.util.concurrent.TimeUnit

class ApiClient(baseUrl: String) {
    private val root = baseUrl.trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    class ApiException(message: String, val code: Int = 0) : Exception(message)
    class DeviceRevokedException : Exception("Касса отозвана на сервере")

    data class EnrollResult(val deviceId: Int, val deviceToken: String, val storeName: String)
    data class Directory(
        val products: List<Product>,
        val storeNames: List<String>,
        val cupsForFree: Int,
        val serverPub: String,
        val rawJson: String
    )
    data class SaleResult(
        val cardToken: String,
        val paidTotal: Int,
        val freeUsed: Int,
        val freeAvailable: Int,
        val cashbackBalance: Int,
        val cupsForFree: Int,
        val appliedFree: Int,
        val appliedCashback: Int,
        val paidCups: Int
    )
    data class SyncResult(val applied: List<String>, val duplicates: List<String>, val rejected: Int)

    fun enroll(code: String, publicKey: String): EnrollResult {
        val jo = JSONObject().put("code", code.trim().uppercase())
        if (publicKey.isNotBlank()) jo.put("publicKey", publicKey)
        val body = jo.toString()
        val req = Request.Builder()
            .url("$root/api/devices/enroll")
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw ApiException(msg?.takeIf { it.isNotBlank() } ?: "Ошибка регистрации (${res.code})", res.code)
            }
            val o = JSONObject(text)
            return EnrollResult(
                deviceId = o.getInt("deviceId"),
                deviceToken = o.getString("deviceToken"),
                storeName = o.optString("storeName", "Точка")
            )
        }
    }

    fun fetchDirectory(deviceToken: String? = null): Directory {
        val b = Request.Builder().url("$root/api/directory").get()
        if (!deviceToken.isNullOrBlank()) b.header("X-Device-Token", deviceToken)
        http.newCall(b.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 401 && !deviceToken.isNullOrBlank()) throw DeviceRevokedException()
            if (!res.isSuccessful) throw ApiException("Каталог недоступен (${res.code})", res.code)
            return parseDirectory(text)
        }
    }

    /** Рецепты — только с токеном кассы. */
    fun fetchStaffRecipes(deviceToken: String): Map<String, Triple<String?, Int?, Int?>> {
        val req = Request.Builder()
            .url("$root/api/directory/staff")
            .header("X-Device-Token", deviceToken)
            .get()
            .build()
        http.newCall(req).execute().use { res ->
            if (res.code == 401) throw DeviceRevokedException()
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) return emptyMap()
            val arr = JSONObject(text).optJSONArray("products") ?: return emptyMap()
            val map = mutableMapOf<String, Triple<String?, Int?, Int?>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optInt("id").toString()
                map[id] = Triple(
                    o.optString("recipeText").ifBlank { null },
                    if (o.has("recipeCostRub") && !o.isNull("recipeCostRub")) o.optInt("recipeCostRub") else null,
                    if (o.has("recipeSeconds") && !o.isNull("recipeSeconds")) o.optInt("recipeSeconds") else null
                )
            }
            return map
        }
    }

    fun postSale(
        deviceToken: String,
        cardToken: String,
        fiscalId: String,
        amountRub: Int,
        useFree: Boolean,
        cashbackUseRub: Int,
        items: List<JSONObject>
    ): SaleResult {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        val body = JSONObject()
            .put("cardToken", cardToken)
            .put("fiscalId", fiscalId)
            .put("amountRub", amountRub)
            .put("useFree", useFree)
            .put("cashbackUseRub", cashbackUseRub)
            .put("items", arr)
            .toString()
        val req = Request.Builder()
            .url("$root/api/devices/sales")
            .header("X-Device-Token", deviceToken)
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 401) throw DeviceRevokedException()
            if (!res.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw ApiException(msg?.takeIf { it.isNotBlank() } ?: "Ошибка лояльности (${res.code})", res.code)
            }
            val o = JSONObject(text)
            return SaleResult(
                cardToken = o.getString("card"),
                paidTotal = o.optInt("paidTotal"),
                freeUsed = o.optInt("freeUsed"),
                freeAvailable = o.optInt("freeAvailable"),
                cashbackBalance = o.optInt("cashbackBalance"),
                cupsForFree = o.optInt("cupsForFree", 5),
                appliedFree = o.optInt("appliedFree"),
                appliedCashback = o.optInt("appliedCashback"),
                paidCups = o.optInt("paidCups")
            )
        }
    }

    fun syncReceipts(deviceToken: String, receipts: List<String>): SyncResult {
        if (receipts.isEmpty()) return SyncResult(emptyList(), emptyList(), 0)
        val arr = JSONArray()
        receipts.forEach { arr.put(it) }
        val body = JSONObject().put("receipts", arr).toString()
        val req = Request.Builder()
            .url("$root/api/devices/sync")
            .header("X-Device-Token", deviceToken)
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 401) throw DeviceRevokedException()
            if (!res.isSuccessful) throw ApiException("Синк ${res.code}", res.code)
            val result = JSONObject(text).optJSONObject("result") ?: return SyncResult(emptyList(), emptyList(), 0)
            val applied = result.optJSONArray("applied") ?: JSONArray()
            val duplicates = result.optJSONArray("duplicates") ?: JSONArray()
            val rejected = result.optJSONArray("rejected") ?: JSONArray()
            return SyncResult(
                applied = (0 until applied.length()).map { applied.getString(it) },
                duplicates = (0 until duplicates.length()).map { duplicates.getString(it) },
                rejected = rejected.length()
            )
        }
    }

    companion object {
        fun parseDirectory(text: String): Directory {
            val o = JSONObject(text)
            val serverPub = o.optString("serverPub", "")
            val cups = o.optInt("cupsForFree", 5)
            val products = mutableListOf<Product>()
            val parr = o.optJSONArray("products") ?: JSONArray()
            val cats = mutableMapOf<Int, String>()
            o.optJSONArray("categories")?.let { ca ->
                for (i in 0 until ca.length()) {
                    val c = ca.getJSONObject(i)
                    cats[c.optInt("id")] = c.optString("name")
                }
            }
            for (i in 0 until parr.length()) {
                val p = parr.getJSONObject(i)
                val id = p.optInt("id", i + 1).toString()
                val name = p.optString("name", "Товар")
                val priceRub = p.optInt("price", 0)
                val catName = cats[p.optInt("categoryId")] ?: ""
                val category = when {
                    catName.contains("нап", true) || catName.contains("drink", true) -> Category.DRINKS
                    catName.contains("ед", true) || catName.contains("food", true) -> Category.FOOD
                    else -> Category.OTHER
                }
                val countsCup = p.optBoolean("countsAsCup", false) ||
                    p.optInt("countsAsCup", 0) == 1
                products.add(
                    Product(
                        id = id,
                        name = name,
                        priceKopecks = priceRub * 100,
                        category = category,
                        isFreeEligible = countsCup,
                        imageUrl = p.optString("imageUrl").ifBlank { null },
                        modifierSchemeId = if (p.has("modifierSchemeId") && !p.isNull("modifierSchemeId"))
                            p.optInt("modifierSchemeId") else null,
                        recipeText = null,
                        recipeCostRub = null,
                        recipeSeconds = null
                    )
                )
            }
            val stores = mutableListOf<String>()
            o.optJSONArray("stores")?.let { sa ->
                for (i in 0 until sa.length()) {
                    stores.add(sa.getJSONObject(i).optString("name"))
                }
            }
            return Directory(products, stores, cups, serverPub, text)
        }
    }
}
