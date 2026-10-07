package ru.sixthcup.evotor.data

import android.content.Context

/**
 * Bound guest card for current sale.
 * [raw] is QR token or short numeric code (identity only).
 * Live balance comes from backend resolve, not from QR payload.
 */
object CardSession {
    private const val PREFS = "sixthcup_card"
    private const val KEY_RAW = "raw"
    private const val KEY_RESERVATION = "reservation_id"
    private const val KEY_FREE = "free_available"
    private const val KEY_PAID = "paid_cups"
    private const val KEY_CASHBACK = "cashback"
    private const val KEY_CODE = "card_code"
    private const val KEY_EXPIRES = "reservation_expires"

    fun get(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RAW, null)

    fun set(ctx: Context, raw: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RAW, raw.trim())
            .remove(KEY_RESERVATION)
            .remove(KEY_FREE)
            .remove(KEY_PAID)
            .remove(KEY_CASHBACK)
            .remove(KEY_CODE)
            .remove(KEY_EXPIRES)
            .apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun setResolve(
        ctx: Context,
        reservationId: String?,
        freeAvailable: Boolean,
        paidCups: Int,
        cashbackKopecks: Int,
        cardCode: String?,
        expiresAt: Long?,
    ) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RESERVATION, reservationId)
            .putBoolean(KEY_FREE, freeAvailable)
            .putInt(KEY_PAID, paidCups)
            .putInt(KEY_CASHBACK, cashbackKopecks)
            .putString(KEY_CODE, cardCode)
            .putLong(KEY_EXPIRES, expiresAt ?: 0L)
            .apply()
    }

    fun reservationId(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RESERVATION, null)

    fun freeAvailable(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FREE, false)

    fun paidCups(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PAID, 0)

    fun cashbackKopecks(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CASHBACK, 0)

    fun cardCode(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CODE, null)
}
