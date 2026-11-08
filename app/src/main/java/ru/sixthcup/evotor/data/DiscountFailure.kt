package ru.sixthcup.evotor.data

import android.content.Context

/**
 * Ошибка применения скидки не должна «глотаться»: причина уходит в лог сервиса,
 * а кассир видит понятное сообщение при следующем возврате на главный экран.
 */
object DiscountFailure {
    private const val PREF = "sixthcup_discount_failure"
    private const val KEY_REASON = "reason"
    private const val KEY_AT = "at"
    private const val TTL_MS = 30 * 60 * 1000L

    fun cashierText(reason: String?): String {
        val r = reason?.trim().orEmpty()
        return if (r.isEmpty()) {
            "Не удалось применить скидку. Позовите администратора"
        } else {
            "Не удалось применить скидку ($r). Позовите администратора"
        }
    }

    /** Запоминает сбой (переживает перезапуск процесса на экране оплаты). */
    fun report(ctx: Context, reason: String?) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_REASON, reason?.trim()?.take(64).orEmpty())
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    /** Забирает сбой один раз; `null` — сбоя не было или он уже устарел. */
    fun take(ctx: Context, nowMs: Long = System.currentTimeMillis()): String? {
        val p = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val at = p.getLong(KEY_AT, 0L)
        if (at == 0L) return null
        val reason = p.getString(KEY_REASON, null) ?: ""
        p.edit().clear().apply()
        if (nowMs - at > TTL_MS) return null
        return reason
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
