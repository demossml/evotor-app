package ru.sixthcup.evotor.data
import android.content.Context
object CardSession {
    private const val PREF = "sixthcup_card"
    private const val KEY = "code"
    fun set(ctx: Context, code: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, code.trim()).apply()
    }
    fun get(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() }
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
