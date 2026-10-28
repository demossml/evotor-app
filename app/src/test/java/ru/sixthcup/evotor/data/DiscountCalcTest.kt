package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class DiscountCalcTest {
    private fun d(s: String) = BigDecimal(s)

    @Test fun freeIsCheapestEligibleUnit() {
        val r = DiscountCalc.compute(d("430"), listOf(d("250"), d("180")), true, true, 1, 0L, 0L)
        assertEquals(d("180.00"), r.free); assertEquals(d("180.00"), r.total)
    }

    @Test fun nothingWithoutReservationOrChoice() {
        assertEquals(d("0.00"), DiscountCalc.compute(d("430"), listOf(d("250")), false, true, 1, 5000L, 5000L).total)
        assertEquals(d("0.00"), DiscountCalc.compute(d("430"), listOf(d("250")), true, false, 1, 0L, 5000L).total)
        assertEquals(d("0.00"), DiscountCalc.compute(d("430"), emptyList(), true, true, 1, 0L, 0L).total)
    }

    @Test fun cashbackBoundedByBalanceAndTotal() {
        assertEquals(d("30.00"), DiscountCalc.compute(d("250"), emptyList(), true, false, 0, 3000L, 3000L).cashback)
        assertEquals(d("99.99"), DiscountCalc.compute(d("100"), emptyList(), true, false, 0, 9999L, 9999L).cashback)
        assertEquals(d("30.00"), DiscountCalc.compute(d("250"), emptyList(), true, false, 0, 9999L, 3000L).cashback)
    }

    @Test fun cashbackAfterFree() {
        val r = DiscountCalc.compute(d("200"), listOf(d("150")), true, true, 1, 9999L, 9999L)
        assertEquals(d("50.00"), r.cashback); assertEquals(d("200.00"), r.total)
    }

    @Test fun kopecksHelper() { assertEquals(3050L, DiscountCalc.kopecks(d("30.50"))) }

    // ── Ваучер: percent от остатка, fixed в копейках, и «ваучер ИЛИ кэшбэк» ──────────────────

    private val percent15 = ResolveVoucher(id = 12, title = "Скидка 15%", kind = "percent", value = 15)
    private val fixed300Rub = ResolveVoucher(id = 13, title = "Скидка 300 ₽", kind = "fixed", value = 30000)

    @Test fun voucherPercentFromGrossWhenNoGift() {
        val r = DiscountCalc.compute(d("400"), emptyList(), true, false, 0, 0L, 0L, voucher = percent15)
        assertEquals(d("60.00"), r.voucher)
        // total — сумма скидки, а не сумма к оплате: так его читает ReceiptDiscountEventResult.
        assertEquals(d("60.00"), r.total)
    }

    @Test fun voucherPercentFromRemainderAfterGift() {
        val r = DiscountCalc.compute(d("400"), listOf(d("200")), true, true, 1, 0L, 0L, voucher = percent15)
        assertEquals(d("200.00"), r.free)
        assertEquals(d("30.00"), r.voucher)
        assertEquals(d("230.00"), r.total)
    }

    @Test fun voucherFixedInKopecksCoversWholeReceipt() {
        val r = DiscountCalc.compute(d("300"), emptyList(), true, false, 0, 0L, 0L, voucher = fixed300Rub)
        assertEquals(d("300.00"), r.voucher)
        assertEquals(d("300.00"), r.total)
    }

    @Test fun voucherFixedCappedByRemainder() {
        val r = DiscountCalc.compute(d("120"), emptyList(), true, false, 0, 0L, 0L, voucher = fixed300Rub)
        assertEquals(d("120.00"), r.voucher)
        assertEquals(d("120.00"), r.total)
        assertEquals(true, r.total.signum() >= 0)
    }

    @Test fun voucherAndCashbackNeverAddUp() {
        val r = DiscountCalc.compute(d("400"), emptyList(), true, false, 0, 9999L, 9999L, voucher = percent15)
        assertEquals(d("60.00"), r.voucher)
        assertEquals(0, r.cashback.signum())
        assertEquals(d("60.00"), r.total)
        assertEquals(true, r.total <= d("400"))
    }

    @Test fun nonStackableVoucherBlockedByGift() {
        val strict = ResolveVoucher(id = 14, title = "Строгий", kind = "percent", value = 15, stackableWithFreeCup = false)
        val r = DiscountCalc.compute(
            d("400"), listOf(d("200")), true, true, 1, 0L, 0L,
            voucher = strict, voucherAllowed = VoucherCalc.allowedTogether(strict, true),
        )
        assertEquals(0, r.voucher.signum())
        assertEquals(d("200.00"), r.free)
        assertEquals(d("200.00"), r.total)
    }

    @Test fun voucherNeedsReservation() {
        val r = DiscountCalc.compute(d("400"), emptyList(), false, false, 0, 0L, 0L, voucher = percent15)
        assertEquals(0, r.voucher.signum())
        assertEquals(0, r.total.signum())
    }

    @Test fun totalNeverExceedsGrossOrGoesNegative() {
        val big = ResolveVoucher(id = 15, title = "Гигант", kind = "fixed", value = 999999)
        val r = DiscountCalc.compute(d("100"), listOf(d("100")), true, true, 1, 9999L, 9999L, voucher = big)
        assertEquals(0, r.total.compareTo(d("100")))
        assertEquals(true, r.total.signum() >= 0)
    }
}
