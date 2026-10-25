package ru.sixthcup.evotor.data

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Единая чистая функция расчёта скидки. Её используют и лист «Лояльность» (предпросмотр),
 * и DiscountIntegrationService (реальная скидка в чеке), чтобы цифры совпадали.
 * Арифметика перенесена из DiscountIntegrationService без изменений.
 */
object DiscountCalc {
    /** Запас для медленного баристы: backend засчитает истёкший резерв, только если бонус никто не занял. */
    const val GRACE_SEC = 300L

    data class Result(val free: BigDecimal, val cashback: BigDecimal, val total: BigDecimal)

    fun reserved(info: CardInfo, nowSec: Long): Boolean =
        info.reservationId != null && (info.reservationExpiresAt == 0L || info.reservationExpiresAt + GRACE_SEC >= nowSec)

    fun kopecks(rub: BigDecimal): Long = rub.multiply(BigDecimal(100)).setScale(0, RoundingMode.DOWN).toLong()

    /** Цена самой дешёвой подарочной единицы (к ней применяется скидка) или null. */
    fun cheapestEligible(eligibleUnitPrices: List<BigDecimal>): BigDecimal? = eligibleUnitPrices.minOrNull()

    fun compute(
        gross: BigDecimal,
        eligibleUnitPrices: List<BigDecimal>,
        reserved: Boolean,
        freeChosen: Boolean,
        freeAvailable: Int,
        requestedCashbackKopecks: Long,
        cardCashbackKopecks: Long,
    ): Result {
        val free = if (reserved && freeChosen && freeAvailable > 0 && eligibleUnitPrices.isNotEmpty())
            eligibleUnitPrices.minOrNull()!!.setScale(2, RoundingMode.DOWN) else BigDecimal.ZERO
        val cb = if (reserved && requestedCashbackKopecks > 0L)
            BigDecimal(requestedCashbackKopecks.coerceAtMost(cardCashbackKopecks)).divide(BigDecimal(100), 2, RoundingMode.DOWN)
                .min(gross.subtract(free).max(BigDecimal.ZERO))
        else BigDecimal.ZERO
        val total = free.add(cb).min(gross).setScale(2, RoundingMode.DOWN)
        return Result(free, cb, total)
    }
}
