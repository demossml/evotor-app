package ru.sixthcup.evotor.data

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Итог по льготам для одного чека в виде, готовом для UI: строки, скидка, к оплате.
 *
 * Числа считает [DiscountCalc] — тот же объект, которым пользуется
 * `DiscountIntegrationService`, поэтому сумма на экране и сумма в чеке совпадают.
 */
data class Benefits(
    val free: BigDecimal,
    val voucher: BigDecimal,
    val cashback: BigDecimal,
    /** Скидка, которая уйдёт в фискальный чек (совпадает с `PayBreakdown.discountKopecks`). */
    val total: BigDecimal,
    val payable: BigDecimal,
    val voucherTitle: String?,
    /** Сработал фискальный пол 1 ₽: чек не может стать нулевым. */
    val floorApplied: Boolean = false,
) {
    val hasAny: Boolean get() = total.signum() > 0
    val hasGift: Boolean get() = free.signum() > 0
    val hasVoucher: Boolean get() = voucher.signum() > 0
    val hasCashback: Boolean get() = cashback.signum() > 0

    /** Короткая подпись для полосы корзины: «подарок −180 ₽ · ваучер −60 ₽». */
    fun shortLabel(): String? {
        val parts = ArrayList<String>(3)
        if (hasGift) parts.add("подарок −${money(free)}")
        if (hasVoucher) parts.add("ваучер −${money(voucher)}")
        if (hasCashback) parts.add("кэшбэк −${money(cashback)}")
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** Строки для блока льгот в корзине и в листе перед оплатой. */
    fun lines(): List<String> {
        val out = ArrayList<String>(3)
        if (hasGift) out.add("Подарок −${money(free)}" + if (floorApplied) " (в чеке 1 ₽)" else "")
        if (hasVoucher) out.add((voucherTitle?.let { "Ваучер «$it»" } ?: "Ваучер") + " −${money(voucher)}")
        if (hasCashback) out.add("Кэшбэк −${money(cashback)}")
        return out
    }

    private fun money(v: BigDecimal): String =
        v.setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString() + " ₽"

    companion object {
        val NONE = Benefits(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null)
    }
}

object LoyaltyPreview {
    /**
     * @param gross сумма чека до скидок (без комбо)
     * @param eligible цены единиц, участвующих в подарке
     */
    fun of(
        card: CardInfo?,
        choice: Choice,
        gross: BigDecimal,
        eligible: List<BigDecimal>,
        nowSec: Long,
    ): Benefits {
        if (card == null || card.code.isEmpty()) return Benefits.NONE.copy(payable = gross.max(BigDecimal.ZERO))
        val reserved = DiscountCalc.reserved(card, nowSec)
        val voucher = choice.voucherId?.let { id -> card.vouchers.firstOrNull { it.id == id } }
        val calc = DiscountCalc.compute(
            gross = gross,
            eligibleUnitPrices = eligible,
            reserved = reserved,
            freeChosen = choice.free,
            freeAvailable = card.freeAvailable,
            requestedCashbackKopecks = choice.cashbackKopecks,
            cardCashbackKopecks = card.cashbackKopecks,
            voucher = voucher,
            voucherAllowed = VoucherCalc.allowedTogether(
                voucher,
                DiscountCalc.freeApplies(reserved, choice.free, card.freeAvailable, eligible),
            ),
        )
        // Итог — из того же расклада, что уходит в позиции и в скидку на чек: цифры не разойдутся.
        val bd = FiscalCalc.split(gross, eligible, card, choice, nowSec)
        val total = BigDecimal(bd.totalDiscountKopecks).divide(BigDecimal(100), 2, RoundingMode.DOWN)
        val payable = BigDecimal(DiscountCalc.kopecks(gross) - bd.totalDiscountKopecks)
            .coerceAtLeast(BigDecimal.ZERO)
            .divide(BigDecimal(100), 2, RoundingMode.DOWN)
        // Подарок реально даёт скидку «цена − 1 ₽»: единица остаётся в чеке за рубль.
        val giftRub = if (calc.free.signum() > 0) {
            calc.free.subtract(FiscalCalc.GIFT_FISCAL_MIN).max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN)
        } else BigDecimal.ZERO
        return Benefits(
            free = giftRub,
            voucher = calc.voucher,
            cashback = calc.cashback,
            total = total,
            payable = payable,
            voucherTitle = if (calc.voucher.signum() > 0) voucher?.title else null,
            floorApplied = bd.floorApplied,
        )
    }

    /** Можно ли предложить гостю выбор льготы сразу после привязки карты. */
    fun canOffer(card: CardInfo?, nowSec: Long): Boolean {
        if (card == null || card.code.isEmpty() || card.errorCode != null) return false
        if (card.freeStatus == "OFFLINE" || card.freeStatus == "ERROR") return false
        // Ручной числовой код не даёт списания: резерва нет, backend льготы не применит.
        if (CardSession.kindOf(card.code) != "token") return false
        if (!DiscountCalc.reserved(card, nowSec)) return false
        return card.freeAvailable > 0 || card.vouchers.isNotEmpty()
    }
}
