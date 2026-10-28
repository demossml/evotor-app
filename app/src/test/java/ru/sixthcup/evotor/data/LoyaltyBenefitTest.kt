package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Единый расчёт льгот для UI: числа в корзине, в листе и в чеке обязаны совпадать,
 * потому что все три места используют [LoyaltyPreview] -> [DiscountCalc].
 */
class LoyaltyBenefitTest {

    private fun d(s: String) = BigDecimal(s)
    private val now = 1_000_000L

    private val voucher15 = ResolveVoucher(id = 12, title = "Скидка 15%", kind = "percent", value = 15)

    private fun card(
        code: String = "payload.signature",
        freeAvailable: Int = 1,
        reservationId: String? = "R-1",
        vouchers: List<ResolveVoucher> = emptyList(),
        freeStatus: String = "RESERVED",
    ) = CardInfo(
        code = code,
        cardCode = "0012",
        paidCups = 3,
        cupsForFree = 5,
        freeAvailable = freeAvailable,
        cashbackKopecks = 5000,
        reservationId = reservationId,
        reservationExpiresAt = 0L,
        freeStatus = freeStatus,
        vouchers = vouchers,
    )

    private fun choice(free: Boolean = false, cashback: Boolean = false, kopecks: Long = 0L, voucher: Int? = null) =
        Choice.normalize(free, cashback, kopecks, voucher)

    @Test
    fun `gift preview equals the discount integration`() {
        // Подарок: единица уходит в чек за 1 ₽, значит скидка = 180 − 1 = 179.
        val b = LoyaltyPreview.of(card(), choice(free = true), d("400"), listOf(d("180")), now)
        assertEquals(d("179.00"), b.free)
        assertEquals(d("179.00"), b.total)
        assertEquals(d("221.00"), b.payable)
        assertTrue(b.hasGift)
        assertTrue(b.floorApplied)
    }

    @Test
    fun `voucher preview is a percent of the remainder`() {
        val b = LoyaltyPreview.of(card(vouchers = listOf(voucher15)), choice(voucher = 12), d("400"), emptyList(), now)
        assertEquals(d("60.00"), b.voucher)
        assertEquals(d("340.00"), b.payable)
        assertEquals("Скидка 15%", b.voucherTitle)
    }

    @Test
    fun `voucher and cashback never both apply`() {
        val b = LoyaltyPreview.of(
            card(vouchers = listOf(voucher15)),
            choice(cashback = true, kopecks = 5000L, voucher = 12),
            d("400"), emptyList(), now,
        )
        assertEquals(d("60.00"), b.voucher)
        assertEquals(0, b.cashback.signum())
    }

    @Test
    fun `gift and voucher are mutually exclusive in the preview`() {
        val c = choice(free = true, voucher = 12)
        val b = LoyaltyPreview.of(card(vouchers = listOf(voucher15)), c, d("400"), listOf(d("180")), now)
        assertFalse(c.free)
        assertEquals(0, b.free.signum())
        assertEquals(d("60.00"), b.voucher)
    }

    @Test
    fun `no reservation means no discount and full price`() {
        val b = LoyaltyPreview.of(
            card(reservationId = null, freeStatus = "NONE", vouchers = listOf(voucher15)),
            choice(free = true, voucher = 12), d("400"), listOf(d("180")), now,
        )
        assertEquals(0, b.total.signum())
        assertEquals(d("400.00"), b.payable)
        assertNull(b.shortLabel())
    }

    @Test
    fun `numeric code never offers or applies benefits`() {
        val manual = card(code = "1234567890", freeStatus = "NONE", reservationId = null)
        assertFalse(LoyaltyPreview.canOffer(manual, now))
        val b = LoyaltyPreview.of(manual, choice(free = true), d("400"), listOf(d("180")), now)
        assertEquals(0, b.total.signum())
    }

    @Test
    fun `offline or failed card never triggers the prompt`() {
        assertFalse(LoyaltyPreview.canOffer(null, now))
        assertFalse(LoyaltyPreview.canOffer(card(freeStatus = "OFFLINE", reservationId = null), now))
        assertFalse(LoyaltyPreview.canOffer(card(freeStatus = "ERROR", reservationId = null), now))
        assertFalse(LoyaltyPreview.canOffer(card(freeAvailable = 0), now))
        assertTrue(LoyaltyPreview.canOffer(card(), now))
        assertTrue(LoyaltyPreview.canOffer(card(freeAvailable = 0, vouchers = listOf(voucher15)), now))
    }

    @Test
    fun `short label lists every applied benefit`() {
        val b = LoyaltyPreview.of(card(), choice(free = true, cashback = true, kopecks = 5000L), d("400"), listOf(d("180")), now)
        val label = b.shortLabel() ?: ""
        assertTrue(label.contains("подарок −179"))
        assertTrue(label.contains("кэшбэк"))
    }

    @Test
    fun `payable never drops below the fiscal floor of one rouble`() {
        // Ваучер больше чека: в ККТ нельзя 0, поэтому к оплате остаётся 1 ₽.
        val big = ResolveVoucher(id = 20, title = "Гигант", kind = "fixed", value = 999999)
        val b = LoyaltyPreview.of(card(vouchers = listOf(big)), choice(voucher = 20), d("100"), emptyList(), now)
        assertEquals(d("1.00"), b.payable)
        assertEquals(true, b.floorApplied)
    }
}
