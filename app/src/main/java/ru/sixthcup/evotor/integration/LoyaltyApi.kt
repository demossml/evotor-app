package ru.sixthcup.evotor.integration

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Live loyalty resolve via 6.7 backend (HTTPS).
 * On Evotor terminal traffic goes through cloud proxy when app is installed from Market
 * and URL is allowlisted in developer cabinet.
 */
object LoyaltyApi {
    /** Production API base — same host as PWA reverse_proxy. */
    const val BASE_URL = "https://app.67coffee.ru"

    data class ResolveResult(
        val cardCode: String?,
        val cardId: String?,
        val paidCups: Int,
        val freeAvailable: Boolean,
        val cashbackKopecks: Int,
        val reservationId: String?,
        val reservationExpiresAt: Long?,
        val benefit: String?,
        val cupsTowardFree: Int,
    )

    fun resolveBlocking(
        cardRef: String,
        storeUuid: String? = null,
        terminalId: String? = null,
        timeoutMs: Int = 8000,
    ): ResolveResult {
        val url = URL("$BASE_URL/api/loyalty/resolve")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        val body = JSONObject().apply {
            put("c", cardRef)
            if (!storeUuid.isNullOrBlank()) put("storeUuid", storeUuid)
            if (!terminalId.isNullOrBlank()) put("terminalId", terminalId)
            put("reserveFree", true)
        }.toString()
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
        if (code !in 200..299) {
            throw RuntimeException("resolve HTTP $code: ${text.take(200)}")
        }
        val json = JSONObject(text)
        return ResolveResult(
            cardCode = json.optString("cardCode", null),
            cardId = json.optString("cardId", null),
            paidCups = json.optInt("paidCups", 0),
            freeAvailable = json.optBoolean("freeAvailable", false),
            cashbackKopecks = json.optInt("cashbackKopecks", 0),
            reservationId = json.optString("reservationId", null).takeIf { it.isNotBlank() && it != "null" },
            reservationExpiresAt = if (json.has("reservationExpiresAt") && !json.isNull("reservationExpiresAt"))
                json.optLong("reservationExpiresAt") else null,
            benefit = json.optString("benefit", null),
            cupsTowardFree = json.optInt("cupsTowardFree", 0),
        )
    }

    private val pool = Executors.newSingleThreadExecutor()

    fun resolveAsync(
        cardRef: String,
        onOk: (ResolveResult) -> Unit,
        onErr: (String) -> Unit,
    ) {
        pool.execute {
            try {
                val r = resolveBlocking(cardRef)
                onOk(r)
            } catch (e: Throwable) {
                onErr(e.message ?: "resolve failed")
            }
        }
    }
}
