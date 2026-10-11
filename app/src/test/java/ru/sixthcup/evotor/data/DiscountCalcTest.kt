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
}
