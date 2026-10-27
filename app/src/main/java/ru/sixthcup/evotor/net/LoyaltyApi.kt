package ru.sixthcup.evotor.net

import android.util.Log
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig
import ru.sixthcup.evotor.data.CardInfo
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calls our backend through the Evotor cloud proxy (HTTPS only, ports 80/443, no WebSocket).
 * The cloud injects the Authorization header configured in the developer cabinet, so there is
 * NO secret in this APK. Timeouts stay below the cloud limits (5 s connect / 10 s read).
 * Blocking: call from a background thread only.
 */
object LoyaltyApi {
    fun resolve(code: String): CardInfo {
        val isToken = code.contains('.')
        val startedAt = System.currentTimeMillis()
        return try {
            val conn = (URL(BuildConfig.API_BASE_URL.trimEnd('/') + "/api/devices/loyalty/resolve").openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            DeviceHeaders.apply(conn)
            conn.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val o = try { JSONObject(body) } catch (_: Throwable) { JSONObject() }
            if (status == 200 && o.optBoolean("ok")) {
                CardInfo(
                    code = code, cardCode = o.optString("cardCode"), paidCups = o.optInt("paidCups"),
                    cupsForFree = o.optInt("cupsForFree", 5), freeAvailable = o.optInt("freeAvailable"),
                    cashbackKopecks = o.optLong("cashback"),
                    reservationId = if (o.isNull("reservationId")) null else o.optString("reservationId").ifBlank { null },
                    reservationExpiresAt = o.optLong("reservationExpiresAt"),
                    freeStatus = o.optString("freeStatus", "NONE"),
                    // Сервер пока не присылает seq; поле читается на будущее и не влияет на чек.
                    seq = if (o.has("seq") && !o.isNull("seq")) o.optInt("seq").takeIf { it > 0 } else null,
                )
            } else {
                // Серверная ошибка нашего API (не проблема карты) — отдельное событие в журнале.
                if (status >= 500 || status == 401 || status == 403) {
                    Log.e(
                        "LoyaltyApi",
                        "http_error POST /api/devices/loyalty/resolve code=$status ms=${System.currentTimeMillis() - startedAt}",
                    )
                }
                val reason = o.optString("error").ifBlank { o.optString("code") }.ifBlank { null }
                // Unknown/invalid card: do not attach it to the receipt at all.
                offline(code, isToken, resolveErrorText(status, reason), drop = resolveDrop(status), errorCode = reason)
            }
        } catch (e: Throwable) {
            offline(code, isToken, if (e is java.net.SocketTimeoutException) "Сервер не ответил вовремя." else "Нет связи с сервером.", drop = false)
        }
    }

    /**
     * Текст для кассира по ответу resolve. Различаем только то, что сервер уже различает
     * (`{ "error": "<code>" }`), иначе остаётся прежняя общая формулировка.
     */
    internal fun resolveErrorText(status: Int, code: String?): String = when {
        code == "card_blocked" -> "Карта заблокирована."
        code == "card_not_found" || code == "invalid_card" ->
            "Карта не найдена. Проверьте номер или отсканируйте QR."
        code == "proxy_not_configured" -> "Сервер лояльности не настроен. Обратитесь к администратору."
        status == 400 || status == 404 -> "Карта не найдена или недействительна. Проверьте номер или QR."
        status == 401 || status == 403 -> "Нет доступа к серверу лояльности ($status). Сообщите администратору."
        status >= 500 -> "Сервер лояльности временно недоступен ($status)."
        else -> "Неожиданный ответ сервера ($status)."
    }

    /** Карту не прикрепляем к чеку, только если сервер сказал, что она недействительна. */
    internal fun resolveDrop(status: Int): Boolean = status == 400 || status == 404

    /**
     * Server unreachable: a signed QR still goes into the receipt so the cup can be credited after the sale
     * (the backend verifies the signature itself) — but WITHOUT any bonus. A bare number is not accepted offline.
     */
    private fun offline(code: String, isToken: Boolean, note: String, drop: Boolean, errorCode: String? = null): CardInfo =
        CardInfo(code = if (drop || !isToken) "" else code, cardCode = "", paidCups = 0, cupsForFree = 5, freeAvailable = 0,
            cashbackKopecks = 0, reservationId = null, reservationExpiresAt = 0, freeStatus = if (drop) "ERROR" else "OFFLINE",
            note = note, errorCode = errorCode)
}
