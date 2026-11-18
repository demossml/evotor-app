package ru.sixthcup.evotor.net

import android.util.Log
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig
import ru.sixthcup.evotor.data.PhoneUtil
import java.net.HttpURLConnection
import java.net.URL

/**
 * Бонусная карта по телефону: lookup/register через тот же Evotor proxy, что и LoyaltyApi.
 * Секретов в APK нет; Authorization добавляет Cloud.
 */
object CustomersApi {

    data class Profile(
        val found: Boolean,
        val cardCode: String? = null,
        val cardId: String? = null,
        val balanceKopecks: Long = 0L,
        val effectiveEarnPercent: Int? = null,
        val earnSource: String? = null,
        val phoneMasked: String? = null,
        val created: Boolean = false,
        val httpStatus: Int = 0,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    fun lookup(phone: String): Profile = request("lookup", phone, consentVersion = null)
    fun register(phone: String, consentVersion: String): Profile = request("register", phone, consentVersion)

    private fun request(path: String, phone: String, consentVersion: String?): Profile {
        val normalized = PhoneUtil.normalize(phone)
        if (!PhoneUtil.isValid(normalized)) {
            return Profile(found = false, error = "invalid_phone")
        }
        if (DeviceAuthPolicy.blockedError(DeviceHeaders.storeUuid()) != null) {
            Log.w("CustomersApi", "customers/$path blocked missing_store")
            return Profile(found = false, error = DeviceAuthPolicy.MISSING_STORE)
        }
        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject().put("phone", normalized).apply {
                if (consentVersion != null) {
                    put("pdConsent", true)
                    put("pdConsentVersion", consentVersion)
                }
            }
            val url = BuildConfig.API_BASE_URL.trimEnd('/') + "/api/devices/customers/$path"
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                DeviceHeaders.apply(this)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status in 200..299) parse(text, status) else {
                val code = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                Log.w("CustomersApi", "POST customers/$path HTTP $status code=$code")
                Profile(found = false, httpStatus = status, error = errorText(status, code))
            }
        } catch (e: Throwable) {
            Log.e("CustomersApi", "customers/$path failed", e)
            Profile(found = false, error = if (e is java.net.SocketTimeoutException) "timeout" else "network")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** Публичный для тестов: ответ может быть плоским или `{profile:{...}}`. */
    internal fun parse(body: String, status: Int = 200): Profile {
        val root = runCatching { JSONObject(body) }.getOrElse {
            return Profile(found = false, httpStatus = status, error = "bad_response")
        }
        if (root.has("ok") && !root.optBoolean("ok", true)) {
            return Profile(
                found = false,
                httpStatus = status,
                error = root.optString("error").ifBlank { "server_error" },
            )
        }
        val o = root.optJSONObject("profile")
            ?: root.optJSONObject("customer")
            ?: root.optJSONObject("result")
            ?: root
        val found = o.optBoolean("found", root.optBoolean("found", false))
        fun str(vararg keys: String): String? {
            for (k in keys) {
                if (o.has(k) && !o.isNull(k)) {
                    val v = o.optString(k).trim()
                    if (v.isNotEmpty()) return v
                }
            }
            return null
        }
        fun long(vararg keys: String): Long? {
            for (k in keys) {
                if (o.has(k) && !o.isNull(k)) {
                    val v = o.optLong(k, Long.MIN_VALUE)
                    if (v != Long.MIN_VALUE) return v
                }
            }
            return null
        }
        fun int(vararg keys: String): Int? {
            for (k in keys) {
                if (o.has(k) && !o.isNull(k)) {
                    val v = o.optInt(k, Int.MIN_VALUE)
                    if (v != Int.MIN_VALUE) return v
                }
            }
            return null
        }
        return Profile(
            found = found,
            cardCode = str("cardCode", "card_code", "code"),
            cardId = str("cardId", "card_id", "id"),
            balanceKopecks = long("balanceKopecks", "balance_kopecks", "balance", "cashback", "bonusBalanceKopecks") ?: 0L,
            effectiveEarnPercent = int("effectiveEarnPercent", "effective_earn_percent", "earnPercent", "percent"),
            earnSource = str("earnSource", "earn_source"),
            // В UI и кэш кладём только маску, даже если сервер вернул полный номер.
            phoneMasked = PhoneUtil.masked(str("phoneMasked", "phone_masked", "phone").orEmpty()).ifEmpty { null },
            created = o.optBoolean("created", false),
            httpStatus = status,
        )
    }

    /** Текст для кассира: без стека и без внутренних кодов. */
    fun errorText(status: Int, code: String? = null): String {
        val c = code?.trim()?.lowercase().orEmpty()
        return when {
            c == "invalid_phone" || status == 400 -> "Проверьте номер телефона: нужен российский номер из 11 цифр."
            c == "consent_required" -> "Нужно согласие клиента на обработку телефона."
            c == DeviceAuthPolicy.MISSING_STORE -> DeviceAuthPolicy.missingStoreMessage()
            c == "unauthorized" || status == 401 || status == 403 -> DeviceAuthPolicy.authMessage(status)
            c == "rate_limited" || status == 429 -> "Слишком много запросов. Подождите минуту и повторите."
            status == 503 || c == "service_unavailable" -> "Сервис бонусов временно недоступен. Повторите позже."
            c == "network" -> "Нет связи с сервером. Проверьте интернет."
            c == "timeout" -> "Сервер не ответил вовремя. Повторите."
            c == "bad_response" -> "Сервер вернул непонятный ответ. Повторите."
            status >= 500 -> "Ошибка сервиса бонусов ($status). Повторите позже."
            c.isNotEmpty() -> "Не удалось выполнить запрос: $c"
            else -> "Не удалось выполнить запрос ($status)."
        }
    }
}
