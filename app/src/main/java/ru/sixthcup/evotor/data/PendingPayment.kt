package ru.sixthcup.evotor.data

import android.content.Context

/**
 * «Чек открыт на оплату, результат не подтверждён». Хранится в SharedPreferences,
 * чтобы экран ожидания переживал перезапуск Activity/процесса.
 */
object PendingPayment {
    private const val PREF = "sixthcup_pending_payment"
    private const val KEY_AT = "at"
    private const val TTL_MS = 30 * 60 * 1000L

    fun set(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    fun active(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val at = p.getLong(KEY_AT, 0L)
        if (at == 0L) return false
        if (System.currentTimeMillis() - at > TTL_MS) { clear(ctx); return false }
        return true
    }

    fun clear(ctx: Context) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply() }
}
