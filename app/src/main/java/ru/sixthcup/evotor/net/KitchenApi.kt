package ru.sixthcup.evotor.net

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kitchen-очередь бариста: касса сама сообщает backend о принятом заказе.
 *
 * ```
 * POST /api/devices/kitchen/orders   → { id, queueNumber, status, createdAt }
 * GET  /api/devices/kitchen/orders?ids=…&storeUuid=… → [ { id, queueNumber, status } ]
 * ```
 *
 * Правила:
 *  - [create] идемпотентен по `clientOrderId`: повтор с тем же id не создаёт второй заказ;
 *  - ошибка сети/5xx не откатывает фискальный чек — вызывающий решает, повторять или нет;
 *  - блокирующие вызовы, только с фонового потока (как [QueueNumberApi]).
 */
object KitchenApi {
    private const val TAG = "KitchenApi"
    private const val PATH = "/api/devices/kitchen/orders"
    private const val TIMEOUT_CONNECT_MS = 3000
    private const val TIMEOUT_READ_MS = 6000

    /** Позиция для бариста: имя, количество, читаемые модификаторы, комментарий. */
    data class Line(
        val name: String,
        val qty: Int,
        val mods: List<String> = emptyList(),
        val note: String? = null,
    )

    data class Created(
        val id: String,
        val queueNumber: String,
        val status: String,
        val createdAt: String? = null,
    )

    data class Status(
        val id: String,
        val queueNumber: String,
        val status: String,
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()

        /** [retryable] — имеет смысл повторить (сеть, таймаут, 5xx, 429). 4xx — финальный ответ. */
        data class Err(
            val error: String,
            val httpStatus: Int = 0,
            val retryable: Boolean = true,
        ) : Result<Nothing>()
    }

    /** Готов ли заказ к выдаче по ответу backend. Регистр и синонимы нормализуются. */
    fun isDone(status: String?): Boolean = when (status?.trim()?.lowercase()) {
        "done", "ready", "completed", "finished" -> true
        else -> false
    }

    /** Тело запроса create. Вынесено отдельно: покрыто юнит-тестами без сети. */
    fun body(
        clientOrderId: String,
        storeUuid: String,
        lines: List<Line>,
        totalKopecks: Long? = null,
        guestUserId: Long? = null,
        cardCodeMask: String? = null,
        queueOrderId: String? = null,
    ): JSONObject {
        val items = JSONArray()
        lines.forEach { l ->
            items.put(
                JSONObject()
                    .put("name", l.name)
                    .put("qty", l.qty.coerceAtLeast(1))
                    .put("mods", JSONArray(l.mods.filter { it.isNotBlank() })),
            )
            l.note?.takeIf { it.isNotBlank() }?.let { note ->
                items.getJSONObject(items.length() - 1).put("note", note)
            }
        }
        return JSONObject()
            .put("clientOrderId", clientOrderId)
            .put("storeUuid", storeUuid)
            .put("items", items)
            .apply {
                totalKopecks?.let { put("totalKopecks", it) }
                guestUserId?.let { put("guestUserId", it) }
                cardCodeMask?.takeIf { it.isNotBlank() }?.let { put("cardCodeMask", it) }
                queueOrderId?.takeIf { it.isNotBlank() }?.let { put("queueOrderId", it) }
            }
    }

    /** Разбор ответа create. Без `id` и `queueNumber` ответ бесполезен — считаем ошибкой. */
    fun parseCreated(o: JSONObject): Created? {
        val id = o.optString("id").trim()
        val number = o.optString("queueNumber").trim()
        if (id.isEmpty() || number.isEmpty()) return null
        return Created(
            id = id,
            queueNumber = number,
            status = o.optString("status").trim().ifBlank { "new" },
            createdAt = o.optString("createdAt").trim().ifBlank { null },
        )
    }

    /**
     * Разбор списка статусов. Принимаем массив, `{"orders": […]}`, `{"data": […]}`
     * и одиночный объект — backend может ответить любым из вариантов.
     */
    fun parseStatuses(text: String): List<Status>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val array = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> {
                    val o = JSONObject(trimmed)
                    o.optJSONArray("orders")
                        ?: o.optJSONArray("data")
                        ?: o.optJSONObject("order")?.let { JSONArray().put(it) }
                        ?: if (o.has("id")) JSONArray().put(o) else null
                }
            } ?: return null
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim()
                if (id.isEmpty()) return@mapNotNull null
                Status(
                    id = id,
                    queueNumber = o.optString("queueNumber").trim(),
                    status = o.optString("status").trim(),
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun create(
        clientOrderId: String,
        storeUuid: String,
        lines: List<Line>,
        totalKopecks: Long? = null,
        guestUserId: Long? = null,
        cardCodeMask: String? = null,
        queueOrderId: String? = null,
    ): Result<Created> {
        if (clientOrderId.isBlank() || lines.isEmpty()) {
            return Result.Err("empty request", retryable = false)
        }
        if (DeviceAuthPolicy.blockedError(storeUuid) != null) {
            Log.w(TAG, "create_blocked missing_store")
            return Result.Err(DeviceAuthPolicy.MISSING_STORE, retryable = false)
        }
        val body = body(clientOrderId, storeUuid, lines, totalKopecks, guestUserId, cardCodeMask, queueOrderId)
        val path = PATH + "?storeUuid=" + android.net.Uri.encode(storeUuid)
        return when (val payload = request("POST", path, body)) {
            is Result.Err -> payload
            is Result.Ok -> {
                val created = parseCreated(JSONObject(payload.value))
                if (created == null) {
                    Log.w(TAG, "create_bad_payload")
                    Result.Err("bad payload", retryable = true)
                } else {
                    Log.i(TAG, "created id=${created.id} number=${created.queueNumber} status=${created.status}")
                    Result.Ok(created)
                }
            }
        }
    }

    fun statuses(ids: List<String>, storeUuid: String): Result<List<Status>> {
        val wanted = ids.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return Result.Ok(emptyList())
        if (DeviceAuthPolicy.blockedError(storeUuid) != null) {
            return Result.Err(DeviceAuthPolicy.MISSING_STORE, retryable = false)
        }
        val path = PATH + "?storeUuid=" + android.net.Uri.encode(storeUuid) +
            "&ids=" + android.net.Uri.encode(wanted.joinToString(","))
        return when (val payload = request("GET", path, null)) {
            is Result.Err -> payload
            is Result.Ok -> parseStatuses(payload.value)
                ?.let { Result.Ok(it) }
                ?: Result.Err("bad payload", retryable = true)
        }
    }

    private fun request(method: String, path: String, body: JSONObject?): Result<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(BuildConfig.API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_CONNECT_MS
                readTimeout = TIMEOUT_READ_MS
                setRequestProperty("Accept", "application/json")
                DeviceHeaders.apply(this)
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                status in 200..299 -> Result.Ok(text)
                // Идемпотентный повтор: заказ с этим clientOrderId уже создан — не дублируем.
                status == 409 -> {
                    Log.i(TAG, "$method ${path.substringBefore('?')} already_exists")
                    Result.Err("already_exists", status, retryable = false)
                }
                status == 401 || status == 403 -> {
                    Log.w(TAG, "$method forbidden status=$status")
                    Result.Err("forbidden", status, retryable = false)
                }
                status == 404 -> {
                    Log.w(TAG, "$method route_missing")
                    Result.Err("not_found", status, retryable = false)
                }
                status == 429 -> Result.Err("rate_limited", status, retryable = true)
                status >= 500 -> Result.Err("server_$status", status, retryable = true)
                else -> Result.Err("http_$status", status, retryable = false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$method failed: ${t.message}")
            Result.Err(t.message ?: "network", retryable = true)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }
}
