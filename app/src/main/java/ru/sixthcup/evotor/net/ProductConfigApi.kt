package ru.sixthcup.evotor.net

import android.util.Log
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig
import ru.sixthcup.evotor.data.AdditionOption
import ru.sixthcup.evotor.data.AdditionScheme
import ru.sixthcup.evotor.data.AdditionsConfig
import ru.sixthcup.evotor.data.MilkOption
import ru.sixthcup.evotor.data.MilkScheme
import ru.sixthcup.evotor.data.MilkConfig
import ru.sixthcup.evotor.data.ProductConfig
import ru.sixthcup.evotor.data.VolumeOption
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

/**
 * Конфигурация конкретного товара из backend через тот же прокси Evotor Cloud, что и каталог.
 *
 * `GET /api/devices/products/{productId}/configuration?storeUuid=…`
 *
 * Авторизацию добавляет облако: в APK нет ни токенов, ни заголовков `Authorization`.
 * Таймауты — под ограничения прокси (connect ≤ 5 с, read ≤ 10 с). Вызывать только с фона.
 */
object ProductConfigApi {
    private const val TAG = "ProductConfig"
    const val SCHEMA_VERSION = 1
    private const val TIMEOUT_CONNECT_MS = 5000
    private const val TIMEOUT_READ_MS = 10000

    data class FetchResult(
        val config: ProductConfig?,
        val httpStatus: Int = 0,
        val error: String? = null,
        /** 404 — маршрут не реализован на backend: работаем по старой схеме каталога. */
        val unsupported: Boolean = false,
        /** 200, но JSON не содержит ни объёмов, ни схем — обычное добавление товара. */
        val noConfiguration: Boolean = false,
    )

    fun fetch(productId: Int, storeUuid: String): FetchResult {
        if (productId <= 0 || storeUuid.isBlank()) return FetchResult(null, 0, "bad request")
        var conn: HttpURLConnection? = null
        return try {
            val url = BuildConfig.API_BASE_URL.trimEnd('/') +
                "/api/devices/products/$productId/configuration?storeUuid=" + android.net.Uri.encode(storeUuid)
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_CONNECT_MS
                readTimeout = TIMEOUT_READ_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Evotor-Store-Uuid", storeUuid)
            }
            DeviceHeaders.apply(conn)
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                status == 404 -> {
                    Log.i(TAG, "config_unsupported product=$productId status=404")
                    FetchResult(null, status, "HTTP 404", unsupported = true)
                }
                status == 401 || status == 403 -> {
                    Log.w(TAG, "config_forbidden product=$productId status=$status")
                    FetchResult(null, status, "Нет доступа к конфигурации товара (HTTP $status)")
                }
                status == 429 -> {
                    Log.w(TAG, "config_rate_limited product=$productId")
                    FetchResult(null, status, "Сервер перегружен, повторите через минуту")
                }
                status !in 200..299 -> {
                    Log.w(TAG, "config_http product=$productId status=$status len=${body.length}")
                    FetchResult(null, status, "Ошибка сервера (HTTP $status)")
                }
                else -> parse(body, productId, storeUuid, status)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "config_failed product=$productId", e)
            FetchResult(null, 0, e.message ?: e.javaClass.simpleName)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** Разбор ответа. Публичный для тестов: повреждённый JSON не должен ронять кассу. */
    fun parse(body: String, expectedProductId: Int, expectedStoreUuid: String, httpStatus: Int = 200): FetchResult =
        try {
            val root = JSONObject(body)
            if (!root.optBoolean("ok", true)) {
                FetchResult(null, httpStatus, root.optString("error").ifBlank { "ok=false" })
            } else {
                val schema = root.optInt("schemaVersion", 0)
                if (schema != SCHEMA_VERSION) {
                    Log.w(TAG, "config_schema_mismatch got=$schema expected=$SCHEMA_VERSION")
                    FetchResult(null, httpStatus, "Несовместимая версия конфигурации ($schema)")
                } else {
                    // Чужая точка: UUID из другого store использовать нельзя.
                    val storeUuid = root.optString("storeUuid").trim()
                    val productId = root.optInt("productId", expectedProductId)
                    if (storeUuid.isNotEmpty() && expectedStoreUuid.isNotEmpty() &&
                        !storeUuid.equals(expectedStoreUuid, ignoreCase = true)
                    ) {
                        Log.w(TAG, "config_store_mismatch want=$expectedStoreUuid got=$storeUuid")
                        FetchResult(null, httpStatus, "Конфигурация другой точки")
                    } else {
                        val config = ProductConfig(
                            schemaVersion = schema,
                            storeUuid = storeUuid.ifEmpty { expectedStoreUuid },
                            productId = productId,
                            volumes = parseVolumes(root.optJSONArray("volumes")),
                            milk = parseMilk(root.optJSONObject("milk")),
                            additions = parseAdditions(root.optJSONObject("additions")),
                        )
                        val empty = config.volumes.size <= 1 && config.milk == null && config.additions == null
                        Log.i(
                            TAG,
                            "config_ok product=$productId volumes=${config.volumes.size} " +
                                "milk=${config.milk?.byProductId?.values?.sumOf { it.options.size } ?: 0} " +
                                "additions=${config.additions?.byProductId?.values?.sumOf { it.options.size } ?: 0} " +
                                "store=${config.storeUuid}",
                        )
                        FetchResult(config, httpStatus, null, noConfiguration = empty)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "config_parse_failed ${e.javaClass.simpleName}")
            FetchResult(null, httpStatus, "Некорректный ответ конфигурации")
        }

    private fun parseVolumes(a: org.json.JSONArray?): List<VolumeOption> {
        if (a == null) return emptyList()
        val out = ArrayList<VolumeOption>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val uuid = o.optString("evotorUuid").trim().ifEmpty { null }
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            out.add(
                VolumeOption(
                    productId = o.optInt("productId", 0),
                    evotorUuid = uuid,
                    name = name,
                    label = o.optString("label").ifBlank { name },
                    sizeValue = if (o.has("sizeValue") && !o.isNull("sizeValue")) o.optInt("sizeValue") else null,
                    sizeUnit = o.optString("sizeUnit").ifBlank { null },
                    priceRub = if (o.has("priceRub") && !o.isNull("priceRub")) money(o.optString("priceRub")) else null,
                    available = if (o.has("available")) o.optBoolean("available", true) else true,
                ),
            )
        }
        return out
    }

    private fun parseMilk(o: JSONObject?): MilkConfig? {
        if (o == null || o == JSONObject.NULL) return null
        val byProduct = linkedMapOf<Int, MilkScheme>()
        val map = o.optJSONObject("byProductId") ?: JSONObject()
        for (key in map.keys()) {
            val pid = key.toIntOrNull() ?: continue
            val scheme = map.optJSONObject(key) ?: continue
            byProduct[pid] = MilkScheme(
                schemeId = if (scheme.has("schemeId") && !scheme.isNull("schemeId")) scheme.optInt("schemeId") else null,
                options = parseMilkOptions(scheme.optJSONArray("options")),
            )
        }
        // Схема без единой опции — то же, что «не назначена»: пустые секции не рисуем.
        if (byProduct.values.all { it.options.isEmpty() }) return null
        return MilkConfig(required = o.optBoolean("required", false), byProductId = byProduct)
    }

    private fun parseMilkOptions(a: org.json.JSONArray?): List<MilkOption> {
        if (a == null) return emptyList()
        val out = ArrayList<MilkOption>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val label = o.optString("label").trim().ifEmpty { o.optString("name").trim() }
            if (label.isEmpty()) continue
            out.add(
                MilkOption(
                    id = o.optInt("id", 0),
                    productId = if (o.has("productId") && !o.isNull("productId")) o.optInt("productId") else null,
                    label = label,
                    evotorUuid = o.optString("evotorUuid").trim().ifEmpty { null },
                    isFree = o.optBoolean("isFree", false),
                    isDefault = o.optBoolean("isDefault", false),
                    // Нет поля — обычное правило: бесплатное не отправляем, платное отправляем.
                    sendToReceipt = if (o.has("sendToReceipt")) o.optBoolean("sendToReceipt", true) else !o.optBoolean("isFree", false),
                    available = if (o.has("available")) o.optBoolean("available", true) else true,
                ),
            )
        }
        return out
    }

    private fun parseAdditions(o: JSONObject?): AdditionsConfig? {
        if (o == null || o == JSONObject.NULL) return null
        val byProduct = linkedMapOf<Int, AdditionScheme>()
        val map = o.optJSONObject("byProductId") ?: JSONObject()
        for (key in map.keys()) {
            val pid = key.toIntOrNull() ?: continue
            val scheme = map.optJSONObject(key) ?: continue
            byProduct[pid] = AdditionScheme(
                schemeId = if (scheme.has("schemeId") && !scheme.isNull("schemeId")) scheme.optInt("schemeId") else null,
                options = parseAdditionOptions(scheme.optJSONArray("options")),
            )
        }
        if (byProduct.values.all { it.options.isEmpty() }) return null
        return AdditionsConfig(required = o.optBoolean("required", false), byProductId = byProduct)
    }

    private fun parseAdditionOptions(a: org.json.JSONArray?): List<AdditionOption> {
        if (a == null) return emptyList()
        val out = ArrayList<AdditionOption>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val name = o.optString("name").trim().ifEmpty { o.optString("label").trim() }
            if (name.isEmpty()) continue
            out.add(
                AdditionOption(
                    id = o.optInt("id", 0),
                    productId = if (o.has("productId") && !o.isNull("productId")) o.optInt("productId") else null,
                    name = name,
                    groupKey = o.optString("groupKey", "extra"),
                    evotorUuid = o.optString("evotorUuid").trim().ifEmpty { null },
                    priceRub = money(o.optString("priceRub")),
                    required = o.optBoolean("required", false),
                    maxCount = o.optInt("maxCount", 1).coerceAtLeast(1),
                    isDefault = o.optBoolean("isDefault", false),
                    zeroOption = o.optBoolean("zeroOption", false),
                    available = if (o.has("available")) o.optBoolean("available", true) else true,
                    sendToReceipt = if (o.has("sendToReceipt")) o.optBoolean("sendToReceipt", true) else true,
                ),
            )
        }
        return out
    }

    private fun money(raw: String): BigDecimal = try {
        BigDecimal(raw.trim())
    } catch (_: Throwable) {
        BigDecimal.ZERO
    }
}
