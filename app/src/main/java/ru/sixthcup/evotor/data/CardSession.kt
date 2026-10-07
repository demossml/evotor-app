package ru.sixthcup.evotor.data

import android.content.Context
import org.json.JSONObject

/**
 * Guest identified for the CURRENT sale only. The QR is just an identity; the balance and the
 * bonus reservation come from the backend (see LoyaltyApi). Nothing here is a source of truth.
 */
data class CardInfo(
    /** Raw scanned value: signed QR token or short numeric card number. */
    val code: String,
    val cardCode: String,
    val paidCups: Int,
    val cupsForFree: Int,
    val freeAvailable: Int,
    val cashbackKopecks: Long,
    val reservationId: String?,
    val reservationExpiresAt: Long,
    /** "RESERVED" | "ALREADY_RESERVED" | "NONE" | "OFFLINE" | "ERROR" */
    val freeStatus: String,
    val note: String? = null,
)

object CardSession {
    private const val PREF = "sixthcup_card"
    private const val KEY = "info"
    private const val KEY_AT = "at"
    /** A scan is only valid for one sale; stale scans must never leak into the next customer's receipt. */
    private const val TTL_MS = 10 * 60 * 1000L

    fun set(ctx: Context, info: CardInfo) {
        val o = JSONObject()
            .put("code", info.code).put("cardCode", info.cardCode).put("paidCups", info.paidCups)
            .put("cupsForFree", info.cupsForFree).put("freeAvailable", info.freeAvailable)
            .put("cb", info.cashbackKopecks).put("res", info.reservationId ?: JSONObject.NULL)
            .put("resExp", info.reservationExpiresAt).put("status", info.freeStatus).put("note", info.note ?: JSONObject.NULL)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, o.toString()).putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    fun get(ctx: Context): CardInfo? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val s = p.getString(KEY, null) ?: return null
        if (System.currentTimeMillis() - p.getLong(KEY_AT, 0L) > TTL_MS) { clear(ctx); return null }
        return try {
            val o = JSONObject(s)
            CardInfo(
                code = o.getString("code"), cardCode = o.optString("cardCode"), paidCups = o.optInt("paidCups"),
                cupsForFree = o.optInt("cupsForFree", 5), freeAvailable = o.optInt("freeAvailable"),
                cashbackKopecks = o.optLong("cb"), reservationId = if (o.isNull("res")) null else o.getString("res"),
                reservationExpiresAt = o.optLong("resExp"), freeStatus = o.optString("status", "NONE"),
                note = if (o.isNull("note")) null else o.getString("note"),
            )
        } catch (_: Throwable) { clear(ctx); null }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** extras.sc written into the receipt. The backend re-checks everything when it reads the SELL. */
    fun sc(info: CardInfo, freeApplied: Boolean, cashbackKopecks: Long): JSONObject {
        val o = JSONObject().put("v", 2).put("c", info.code).put("ts", System.currentTimeMillis() / 1000)
        if (info.reservationId != null) o.put("op", info.reservationId)
        o.put("free", if (freeApplied) 1 else 0).put("cb", cashbackKopecks)
        return o
    }
}
