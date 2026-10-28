package ru.sixthcup.evotor.data

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Единая чистая функция расчёта скидки. Её используют и лист «Лояльность» (предпросмотр),
 * и DiscountIntegrationService (реальная скидка в чеке), чтобы цифры совпадали.
 * Арифметика перенесена из DiscountIntegrationService без изменений.
 *
 * Инвариант: ваучер и кэшбэк не складываются. Если ваучер применён, кэшбэк в чек не идёт.
 */
object DiscountCalc {
    /** Запас для медленного баристы: backend засчитает истёкший резерв, только если бонус никто не занял. */
    const val GRACE_SEC = 300L

    /**
     * Минимальная фискальная сумма, когда применён подарок или ваучер.
     * Пробивать 0 ₽ нельзя: чек с нулевой позицией бессмысленен для 54-ФЗ, поэтому
     * гость платит 1 ₽, а скидка уменьшается на эту сумму.
     */
    const val GIFT_FISCAL_MIN_KOPECKS = 100L

    data class Result(
        val free: BigDecimal,
        val voucher: BigDecimal,
        val cashback: BigDecimal,
        val total: BigDecimal,
    )

    fun reserved(info: CardInfo, nowSec: Long): Boolean =
        info.reservationId != null && (info.reservationExpiresAt == 0L || info.reservationExpiresAt + GRACE_SEC >= nowSec)

    fun kopecks(rub: BigDecimal): Long = rub.multiply(BigDecimal(100)).setScale(0, RoundingMode.DOWN).toLong()

    /** Цена самой дешёвой подарочной единицы (к ней применяется скидка) или null. */
    fun cheapestEligible(eligibleUnitPrices: List<BigDecimal>): BigDecimal? = eligibleUnitPrices.minOrNull()

    /** Сработает ли подарок: нужен резерв, выбор кассира и хоть одна подарочная позиция. */
    fun freeApplies(
        reserved: Boolean,
        freeChosen: Boolean,
        freeAvailable: Int,
        eligibleUnitPrices: List<BigDecimal>,
    ): Boolean = reserved && freeChosen && freeAvailable > 0 && eligibleUnitPrices.isNotEmpty()

    /**
     * Скидка по ваучеру. `percent` — процент от остатка после подарка (вниз до копеек),
     * `fixed` — сумма в **копейках** (backend хранит её так), не больше остатка.
     */
    fun voucherDiscount(
        remaining: BigDecimal,
        voucher: ResolveVoucher?,
        reserved: Boolean = true,
        allowed: Boolean = true,
    ): BigDecimal {
        if (voucher == null || !reserved || !allowed) return BigDecimal.ZERO
        val base = remaining.max(BigDecimal.ZERO)
        if (base.signum() == 0) return BigDecimal.ZERO
        val raw = if (voucher.isPercent) {
            base.multiply(BigDecimal(voucher.value)).divide(BigDecimal(100), 2, RoundingMode.DOWN)
        } else {
            BigDecimal(voucher.value).divide(BigDecimal(100), 2, RoundingMode.DOWN)
        }
        return raw.min(base).max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN)
    }

    fun compute(
        gross: BigDecimal,
        eligibleUnitPrices: List<BigDecimal>,
        reserved: Boolean,
        freeChosen: Boolean,
        freeAvailable: Int,
        requestedCashbackKopecks: Long,
        cardCashbackKopecks: Long,
        /** Выбранный ваучер. `null` — ваучер не выбран, расчёт как раньше. */
        voucher: ResolveVoucher? = null,
        /** false, если ваучер нельзя применить вместе с подарком (stackableWithFreeCup = false). */
        voucherAllowed: Boolean = true,
    ): Result {
        val free = if (freeApplies(reserved, freeChosen, freeAvailable, eligibleUnitPrices))
            eligibleUnitPrices.minOrNull()!!.setScale(2, RoundingMode.DOWN) else BigDecimal.ZERO
        val remaining = gross.subtract(free).max(BigDecimal.ZERO)
        val voucherRub = voucherDiscount(remaining, voucher, reserved, voucherAllowed)
        // Кэшбэк — только когда денежная скидка ваучера не применена: суммировать нельзя.
        val cb = if (reserved && requestedCashbackKopecks > 0L && voucherRub.signum() == 0)
            BigDecimal(requestedCashbackKopecks.coerceAtMost(cardCashbackKopecks)).divide(BigDecimal(100), 2, RoundingMode.DOWN)
                .min(remaining.subtract(voucherRub).max(BigDecimal.ZERO))
        else BigDecimal.ZERO
        // total — это СУММА СКИДКИ (так её читает ReceiptDiscountEventResult), а не сумма к оплате.
        val total = free.add(voucherRub).add(cb).min(gross).setScale(2, RoundingMode.DOWN)
        return Result(free, voucherRub, cb, total)
    }

    /**
     * Итог для кассы: сколько скидки уйдёт в фискальный чек и сколько останется к оплате.
     * Один источник для UI и `DiscountIntegrationService`, чтобы цифры не расходились.
     */
    fun breakdown(
        gross: BigDecimal,
        eligibleUnitPrices: List<BigDecimal>,
        card: CardInfo?,
        choice: Choice,
        nowSec: Long,
    ): PayBreakdown {
        val grossKopecks = kopecks(gross).coerceAtLeast(0L)
        if (card == null || card.code.isEmpty()) {
            return PayBreakdown(grossKopecks, 0L, grossKopecks, giftApplied = false, voucherId = null, floorApplied = false)
        }
        val reserved = reserved(card, nowSec)
        val voucher = choice.voucherId?.let { id -> card.vouchers.firstOrNull { it.id == id } }
        val allowed = VoucherCalc.allowedTogether(
            voucher,
            freeApplies(reserved, choice.free, card.freeAvailable, eligibleUnitPrices),
        )
        val result = compute(
            gross, eligibleUnitPrices, reserved, choice.free, card.freeAvailable,
            choice.cashbackKopecks, card.cashbackKopecks, voucher, allowed,
        )
        val giftApplied = result.free.signum() > 0
        val voucherApplied = result.voucher.signum() > 0
        var discount = kopecks(result.total).coerceIn(0L, grossKopecks)
        var net = (grossKopecks - discount).coerceAtLeast(0L)
        val needsFloor = giftApplied || voucherApplied
        val floor = needsFloor && net < GIFT_FISCAL_MIN_KOPECKS && grossKopecks >= GIFT_FISCAL_MIN_KOPECKS
        if (floor) {
            net = GIFT_FISCAL_MIN_KOPECKS
            discount = grossKopecks - net
        } else if (needsFloor && grossKopecks < GIFT_FISCAL_MIN_KOPECKS) {
            // Чек дешевле рубля не раздуваем и не обнуляем: скидки нет, платим фактическую сумму.
            discount = 0L
            net = grossKopecks
        }
        return PayBreakdown(
            grossKopecks = grossKopecks,
            discountKopecks = discount,
            netKopecks = net,
            giftApplied = giftApplied,
            voucherId = if (voucherApplied) voucher?.id else null,
            floorApplied = floor,
        )
    }
}

/** Разбивка чека в копейках: что уйдёт скидкой и что останется к оплате. */
data class PayBreakdown(
    val grossKopecks: Long,
    val discountKopecks: Long,
    val netKopecks: Long,
    val giftApplied: Boolean,
    val voucherId: Int?,
    /** Сработал ли фискальный пол 1 ₽ (скидка урезана, чтобы чек не стал нулевым). */
    val floorApplied: Boolean,
)
