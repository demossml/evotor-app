package ru.sixthcup.evotor.data

import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.net.DeviceKeys

/**
 * Карта клиента: payload.sig (Ed25519, serverPub из /api/directory).
 * DEMO/FREE/CB и «любой код» — запрещены.
 */
object CardParser {
    fun parse(token: String, serverPubB64u: String?): ClientCard? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        if (serverPubB64u.isNullOrBlank()) return null

        val parts = trimmed.split('.')
        if (parts.size != 2) return null
        val (payloadB64, sigB64) = parts
        return try {
            val payloadBytes = DeviceKeys.b64uDecode(payloadB64)
            val sigBytes = DeviceKeys.b64uDecode(sigB64)
            if (!DeviceKeys.verify(payloadBytes, sigBytes, serverPubB64u)) return null
            val o = JSONObject(String(payloadBytes, Charsets.UTF_8))
            if (o.optString("t") != "c") return null
            val userId = o.optInt("u", -1)
            if (userId <= 0) return null
            var couponPercent: Int? = null
            var couponFixed: Int? = null
            val v = o.optJSONArray("v")
            if (v != null) {
                for (i in 0 until v.length()) {
                    val row = v.optJSONArray(i) ?: continue
                    if (row.length() < 3) continue
                    when (row.optString(1)) {
                        "p" -> couponPercent = row.optInt(2)
                        "f" -> couponFixed = row.optInt(2)
                    }
                }
            }
            ClientCard(
                userId = userId,
                paidTotal = o.optInt("p", 0),
                freeUsed = o.optInt("f", 0),
                cashbackRub = o.optInt("cb", 0),
                cupsForFree = 5,
                couponPercent = couponPercent,
                couponFixedRub = couponFixed,
                rawToken = trimmed
            )
        } catch (_: Exception) {
            null
        }
    }
}
