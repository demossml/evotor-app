package ru.sixthcup.evotor.data

import android.content.Context

/** One-check cashier decisions. They are cleared when the checkout integration consumes them. */
object CheckoutChoice {
    private const val PREF = "sixthcup_checkout_choice"
    fun save(context: Context, free: Boolean, cashback: Boolean, cashbackKopecks: Long) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("free", free).putBoolean("cashback", cashback)
            .putLong("cashbackKopecks", cashbackKopecks.coerceAtLeast(0L)).apply()
    }
    fun free(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("free", false)
    fun cashback(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("cashback", false)
    fun cashbackKopecks(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("cashbackKopecks", 0L)
    fun clear(context: Context) { context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply() }
}
