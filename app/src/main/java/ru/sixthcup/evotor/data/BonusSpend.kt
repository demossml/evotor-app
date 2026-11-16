package ru.sixthcup.evotor.data

/** Сколько бонусов можно списать: не больше баланса и не больше суммы корзины. */
object BonusSpend {

    fun maxSpendKopecks(balanceKopecks: Long, cartKopecks: Long): Long =
        minOf(balanceKopecks.coerceAtLeast(0L), cartKopecks.coerceAtLeast(0L))

    fun clamp(requestedKopecks: Long, balanceKopecks: Long, cartKopecks: Long): Long =
        requestedKopecks.coerceIn(0L, maxSpendKopecks(balanceKopecks, cartKopecks))
}
