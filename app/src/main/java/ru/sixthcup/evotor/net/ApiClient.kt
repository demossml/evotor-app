package ru.sixthcup.evotor.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig
import ru.sixthcup.evotor.data.Category
import ru.sixthcup.evotor.data.Product
import java.util.concurrent.TimeUnit

class ApiClient(baseUrl: String) {
    private val root = baseUrl.trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    data class EnrollResult(val deviceId: Int, val deviceToken: String, val storeName: String)
    data class Directory(
        val products: List<Product>,
        val storeNames: List<String>,
        val cupsForFree: Int,
        val rawJson: String
    )

    fun enroll(code: String, publicKey: String): EnrollResult {
        val body = JSONObject()
            .put("code", code.trim().uppercase())
            .put("publicKey", publicKey)
            .toString()
        val req = Request.Builder()
            .url("$root/api/devices/enroll")
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw ApiException(msg?.takeIf { it.isNotBlank() } ?: "Ошибка регистрации (${res.code})")
            }
            val o = JSONObject(text)
            return EnrollResult(
                deviceId = o.getInt("deviceId"),
                deviceToken = o.getString("deviceToken"),
                storeName = o.optString("storeName", "Точка")
            )
        }
    }

    fun fetchDirectory(): Directory {
        val req = Request.Builder().url("$root/api/directory").get().build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException("Каталог недоступен (${res.code})")
            return parseDirectory(text)
        }
    }

    fun syncReceipts(deviceToken: String, receipts: List<String>): Boolean {
        if (receipts.isEmpty()) return true
        val arr = JSONArray()
        receipts.forEach { arr.put(it) }
        val body = JSONObject().put("receipts", arr).toString()
        val req = Request.Builder()
            .url("$root/api/devices/sync")
            .header("Authorization", "Bearer $deviceToken")
            .post(body.toRequestBody(jsonMedia))
            .build()
        return try {
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun defaultBase(): String = BuildConfig.API_BASE_URL

        fun parseDirectory(text: String): Directory {
            val o = JSONObject(text)
            val catMap = mutableMapOf<Int, String>()
            o.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    catMap[c.getInt("id")] = c.optString("name")
                }
            }
            val products = mutableListOf<Product>()
            val arr = o.optJSONArray("products") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val id = p.opt("id")?.toString() ?: continue
                val name = p.optString("name")
                val priceRub = if (p.has("price")) p.getInt("price") else 0
                val catId = if (p.has("categoryId") && !p.isNull("categoryId")) p.getInt("categoryId") else null
                val catName = catId?.let { catMap[it] }.orEmpty()
                val category = mapCategory(catName, name)
                val imageUrl = p.optString("imageUrl").takeIf { it.isNotBlank() }
                    ?: p.optString("image_url").takeIf { it.isNotBlank() }
                products.add(
                    Product(
                        id = id,
                        name = name,
                        priceKopecks = priceRub * 100,
                        category = category,
                        isFreeEligible = category == Category.DRINKS,
                        imageUrl = imageUrl
                    )
                )
            }
            val stores = mutableListOf<String>()
            o.optJSONArray("stores")?.let { s ->
                for (i in 0 until s.length()) {
                    stores.add(s.getJSONObject(i).optString("name"))
                }
            }
            return Directory(products, stores, o.optInt("cupsForFree", 5), text)
        }

        private fun mapCategory(catName: String, productName: String): Category {
            val n = (catName + " " + productName).lowercase()
            return when {
                listOf("напит", "кофе", "чай", "латте", "капуч", "амер", "drink", "coffee")
                    .any { n.contains(it) } -> Category.DRINKS
                listOf("еда", "выпеч", "food", "круасс", "сэндв").any { n.contains(it) } -> Category.FOOD
                else -> Category.OTHER
            }
        }
    }
}

class ApiException(message: String) : Exception(message)
