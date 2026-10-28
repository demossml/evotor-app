package ru.sixthcup.evotor.data

/**
 * Решения кассира на один чек после нормализации.
 *
 * Инварианты кассы:
 * 1. на чек действует **одна** денежная скидка — либо ваучер, либо кэшбэк (backend спишет оба поля);
 * 2. подарок и ваучер **взаимоисключающие**: гость выбирает что-то одно на чек.
 *
 * Правила живут здесь — в модели состояния, а не только в UI.
 */
data class Choice(
    val free: Boolean,
    val cashback: Boolean,
    val cashbackKopecks: Long,
    val voucherId: Int?,
) {
    companion object {
        /** Приводит выбор к состоянию, в котором ваучер, подарок и кэшбэк не конфликтуют. */
        fun normalize(free: Boolean, cashback: Boolean, cashbackKopecks: Long, voucherId: Int?): Choice {
            val voucher = voucherId?.takeIf { it > 0 }
            return when {
                // Ваучер вытесняет и кэшбэк, и подарок: на чек одна льгота.
                voucher != null -> Choice(free = false, cashback = false, cashbackKopecks = 0L, voucherId = voucher)
                cashback -> Choice(free, cashback = true, cashbackKopecks.coerceAtLeast(0L), voucherId = null)
                else -> Choice(free, cashback = false, cashbackKopecks = 0L, voucherId = null)
            }
        }
    }
}
