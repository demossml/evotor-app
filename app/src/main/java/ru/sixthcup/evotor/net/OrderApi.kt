package ru.sixthcup.evotor.net

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Device pre-order API.
 * Blocking: call from background thread only.
 */
object OrderApi {
    private const val TAG = "OrderApi"

    data class Modifier(
        val group: String,
        val id: String,
        val name: String,
        val priceEstimate: Int,
        val sendToReceipt: Boolean,
    )

    data class Item(
        val lineId: String,
        val productId: String,
        val evotorProductUuid: String?,
        val name: String,
        val quantity: Int,
        val volumeLabel: String?,
        val configText: String,
        val unitPriceEstimate: Int,
        val modifiers: List<Modifier>,
    )

    data class Order(
        val id: String,
        val shortCode: String,
        val storeId: Int,
        val status: String,
        val createdAt: String,
        val expiresAt: String,
        val estimateTotal: Int,
        val currency: String,
        val cardId: String?,
        val items: List<Item>,
        val warnings: List<String>,
    )

    data class ActiveRow(
        val id: String,
        val shortCode: String,
        val status: String,
        val createdAt: String,
        val estimateTotal: Int,
        val itemsCount: Int,
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val error: String, val message: String) : Result<Nothing>()
    }

    fun resolveByOid(oid: String): Result<Order> = resolve("oid" to oid)

    fun resolveByCode(code: String): Result<Order> = resolve("code" to code)

    private fun resolve(param: Pair<String, String>): Result<Order> {
        val q = "${param.first}=${java.net.URLEncoder.encode(param.second, "UTF-8")}"
        return get("/api/devices/orders/resolve?$q") { o ->
            parseOrder(o.getJSONObject("order"))
        }
    }

    fun active(): Result<List<ActiveRow>> =
        get("/api/devices/orders/active") { o ->
            val arr = o.optJSONArray("orders") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    add(
                        ActiveRow(
                            id = r.getString("id"),
                            shortCode = r.getString("shortCode"),
                            status = r.getString("status"),
                            createdAt = r.optString("createdAt"),
                            estimateTotal = r.optInt("estimateTotal"),
                            itemsCount = r.optInt("itemsCount"),
                        ),
                    )
                }
            }
        }

    fun setStatus(id: String, status: String): Result<Order> =
        patch("/api/devices/orders/$id/status", JSONObject().put("status", status)) { o ->
            parseOrder(o.getJSONObject("order"))
        }

    fun reject(id: String, reason: String): Result<Unit> =
        post("/api/devices/orders/$id/reject", JSONObject().put("reason", reason)) { Unit }

    fun markPaid(id: String): Result<Order> =
        post("/api/devices/orders/$id/mark-paid", JSONObject()) { o ->
            parseOrder(o.getJSONObject("order"))
        }

    private fun parseOrder(o: JSONObject): Order {
        val itemsArr = o.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until itemsArr.length()) {
                val it = itemsArr.getJSONObject(i)
                val modsArr = it.optJSONArray("modifiers") ?: JSONArray()
                val mods = buildList {
                    for (j in 0 until modsArr.length()) {
                        val m = modsArr.getJSONObject(j)
                        add(
                            Modifier(
                                group = m.optString("group"),
                                id = m.optString("id"),
                                name = m.optString("name"),
                                priceEstimate = m.optInt("priceEstimate"),
                                sendToReceipt = m.optBoolean("sendToReceipt"),
                            ),
                        )
                    }
                }
                add(
                    Item(
                        lineId = it.optString("lineId"),
                        productId = it.optString("productId"),
                        evotorProductUuid = it.optString("evotorProductUuid").ifBlank { null },
                        name = it.optString("name"),
                        quantity = it.optInt("quantity", 1),
                        volumeLabel = it.optString("volumeLabel").ifBlank { null },
                        configText = it.optString("configText"),
                        unitPriceEstimate = it.optInt("unitPriceEstimate"),
                        modifiers = mods,
                    ),
                )
            }
        }
        val warningsArr = o.optJSONArray("warnings") ?: JSONArray()
        val warnings = buildList {
            for (i in 0 until warningsArr.length()) add(warningsArr.getString(i))
        }
        return Order(
            id = o.getString("id"),
            shortCode = o.getString("shortCode"),
            storeId = o.optInt("storeId"),
            status = o.getString("status"),
            createdAt = o.optString("createdAt"),
            expiresAt = o.optString("expiresAt"),
            estimateTotal = o.optInt("estimateTotal"),
            currency = o.optString("currency", "RUB"),
            cardId = o.optString("cardId").ifBlank { null },
            items = items,
            warnings = warnings,
        )
    }

    private fun <T> get(path: String, parse: (JSONObject) -> T): Result<T> =
        request("GET", path, null, parse)

    private fun <T> post(path: String, body: JSONObject, parse: (JSONObject) -> T): Result<T> =
        request("POST", path, body, parse)

    private fun <T> patch(path: String, body: JSONObject, parse: (JSONObject) -> T): Result<T> =
        request("PATCH", path, body, parse)

    private fun <T> request(method: String, path: String, body: JSONObject?, parse: (JSONObject) -> T): Result<T> {
        val started = System.currentTimeMillis()
        return try {
            val conn = (URL(BuildConfig.API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection)
            conn.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            DeviceHeaders.apply(conn)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            // Prefer real PATCH when supported
            if (method == "PATCH") {
                try {
                    val m = conn.javaClass.getMethod("setRequestMethod", String::class.java)
                    // already set; some stacks need conn.requestMethod = "PATCH"
                    conn.requestMethod = "PATCH"
                } catch (_: Throwable) {
                }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val o = try {
                JSONObject(text)
            } catch (_: Throwable) {
                JSONObject()
            }
            if (status in 200..299 && o.optBoolean("ok")) {
                Result.Ok(parse(o))
            } else {
                Log.w(TAG, "$method $path status=$status ms=${System.currentTimeMillis() - started} body=$text")
                Result.Err(
                    error = o.optString("error").ifBlank { "http_$status" },
                    message = o.optString("message").ifBlank { "Ошибка сервера ($status)" },
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "$method $path failed", e)
            Result.Err("network", e.message ?: "Нет связи с сервером")
        }
    }
}
