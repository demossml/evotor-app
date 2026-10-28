package ru.sixthcup.evotor.data

/**
 * Решения кассира на один чек после нормализации.
 *
 * Главный инвариант кассы: на чек действует **одна** денежная скидка — либо ваучер, либо кэшбэк.
 * Backend такого запрета не держит (спишет оба поля), поэтому правило живёт здесь — в модели
 * состояния, а не только в UI.
 */
data class Choice(
    val free: Boolean,
    val cashback: Boolean,
    val cashbackKopecks: Long,
    val voucherId: Int?,
) {
    companion object {
        /** Приводит выбор к состоянию, в котором ваучер и кэшбэк не сосуществуют. */
        fun normalize(free: Boolean, cashback: Boolean, cashbackKopecks: Long, voucherId: Int?): Choice {
            val voucher = voucherId?.takeIf { it > 0 }
            return when {
                voucher != null -> Choice(free, cashback = false, cashbackKopecks = 0L, voucherId = voucher)
                cashback -> Choice(free, cashback = true, cashbackKopecks.coerceAtLeast(0L), voucherId = null)
                else -> Choice(free, cashback = false, cashbackKopecks = 0L, voucherId = null)
            }
        }
    }
}
