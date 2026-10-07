package ru.sixthcup.evotor.net

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
        return try {
            val conn = (URL(BuildConfig.API_BASE_URL.trimEnd('/') + "/api/devices/loyalty/resolve").openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val o = try { JSONObject(body) } catch (_: Throwable) { JSONObject() }
            if (status == 200 && o.optBoolean("ok")) {
                CardInfo(
                    code = code, cardCode = o.optString("cardCode"), paidCups = o.optInt("paidCups"),
                    cupsForFree = o.optInt("cupsForFree", 5), freeAvailable = o.optInt("freeAvailable"),
                    cashbackKopecks = o.optLong("cashbackReserved"),
                    reservationId = if (o.isNull("reservationId")) null else o.optString("reservationId").ifBlank { null },
                    reservationExpiresAt = o.optLong("reservationExpiresAt"),
                    freeStatus = o.optString("freeStatus", "NONE"),
                )
            } else {
                val err = o.optString("error")
                // Unknown/invalid card: do not attach it to the receipt at all.
                if (status == 404 || status == 400) offline(code, isToken, "Карта не найдена или недействительна ($err)", drop = true)
                else offline(code, isToken, "Сервер ответил $status", drop = false)
            }
        } catch (e: Throwable) {
            offline(code, isToken, "Нет связи с сервером", drop = false)
        }
    }

    /**
     * Server unreachable: a signed QR still goes into the receipt so the cup can be credited after the sale
     * (the backend verifies the signature itself) — but WITHOUT any bonus. A bare number is not accepted offline.
     */
    private fun offline(code: String, isToken: Boolean, note: String, drop: Boolean): CardInfo =
        CardInfo(code = if (drop || !isToken) "" else code, cardCode = "", paidCups = 0, cupsForFree = 5, freeAvailable = 0,
            cashbackKopecks = 0, reservationId = null, reservationExpiresAt = 0, freeStatus = if (drop) "ERROR" else "OFFLINE", note = note)
}
