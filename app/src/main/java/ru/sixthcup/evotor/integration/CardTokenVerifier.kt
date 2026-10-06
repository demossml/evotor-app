package ru.sixthcup.evotor.integration

import android.util.Base64
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig

internal data class VerifiedCard(
    val cardId: String,
    val seq: Long,
    val paidCups: Long,
    val freeUsed: Long,
    val cashbackKopecks: Long,
    val vouchers: JSONArray,
    val expiresAt: Long,
)

internal object CardTokenVerifier {
    fun verify(token: String): VerifiedCard? {
        val parts = token.split('.')
        if (parts.size != 2) return null
        val payloadBytes = decode(parts[0]) ?: return null
        val signature = decode(parts[1]) ?: return null
        val payload = try { JSONObject(String(payloadBytes, Charsets.UTF_8)) } catch (_: Throwable) { return null }
        if (payload.optString("t") != "c" || payload.optInt("ver", 0) != 2) return null
        if (payload.optString("kid") != BuildConfig.SERVER_KEY_ID) return null
        if (payload.optLong("exp", 0) <= System.currentTimeMillis() / 1000) return null
        if (BuildConfig.SERVER_PUBLIC_KEY.isBlank()) return null

        return try {
            val pub = decode(BuildConfig.SERVER_PUBLIC_KEY) ?: return null
            val spec = EdDSAPublicKeySpec(pub, EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519))
            val key = EdDSAPublicKey(spec)
            val verifier = EdDSAEngine()
            verifier.initVerify(key)
            verifier.update(payloadBytes)
            if (!verifier.verify(signature)) return null
            VerifiedCard(
                cardId = payload.getString("id"),
                seq = payload.optLong("q", 0),
                paidCups = payload.optLong("p", 0),
                freeUsed = payload.optLong("f", 0),
                cashbackKopecks = payload.optLong("cb", 0),
                vouchers = payload.optJSONArray("v") ?: JSONArray(),
                expiresAt = payload.optLong("exp"),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun decode(value: String): ByteArray? = try {
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    } catch (_: Throwable) { null }
}
