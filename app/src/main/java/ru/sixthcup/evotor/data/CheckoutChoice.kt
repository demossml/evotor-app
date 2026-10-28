package ru.sixthcup.evotor.data

import android.content.Context
import java.math.BigDecimal

/** One-check cashier decisions. They are cleared when the checkout integration consumes them. */
object CheckoutChoice {
    private const val PREF = "sixthcup_checkout_choice"
    fun save(context: Context, free: Boolean, cashback: Boolean, cashbackKopecks: Long, voucherId: Int? = null) {
        val choice = Choice.normalize(free, cashback, cashbackKopecks, voucherId)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("free", choice.free).putBoolean("cashback", choice.cashback)
            .putLong("cashbackKopecks", choice.cashbackKopecks)
            .putInt("voucherId", choice.voucherId ?: 0).apply()
    }
    fun free(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("free", false)
    fun cashback(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("cashback", false)
    fun cashbackKopecks(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("cashbackKopecks", 0L)

    /** Выбранный ваучер (null — не выбран). Пишется в sc только при живом резерве по QR. */
    fun voucherId(context: Context): Int? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("voucherId", 0).takeIf { it > 0 }

    /**
     * Скидка комбо на текущий чек (рубли). Считается по корзине при каждом изменении,
     * применяется в [ru.sixthcup.evotor.integration.DiscountIntegrationService].
     */
    fun saveCombo(context: Context, rub: BigDecimal) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("comboRub", rub.max(BigDecimal.ZERO).toPlainString()).apply()
    }

    fun comboRub(context: Context): BigDecimal = try {
        BigDecimal(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("comboRub", "0") ?: "0")
    } catch (_: Throwable) {
        BigDecimal.ZERO
    }
    fun clear(context: Context) { context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply() }
}
