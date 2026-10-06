package ru.sixthcup.evotor.data
import android.content.Context

/** Card attached for current sale: signed token or short numeric code. */
object CardSession {
    private const val PREF = "sixthcup_card"
    private const val KEY = "value"
    private const val KEY_AT = "at"
    private const val TTL_MS = 30 * 60 * 1000L

    fun set(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, value.trim())
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun get(ctx: Context): String? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val v = p.getString(KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        val at = p.getLong(KEY_AT, 0L)
        if (at > 0 && System.currentTimeMillis() - at > TTL_MS) {
            clear(ctx)
            return null
        }
        return v
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
