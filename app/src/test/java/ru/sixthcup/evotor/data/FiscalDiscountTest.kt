package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Фискальный расклад: подарок и ваучер уходят ценами позиций (`priceWithDiscountPosition`),
 * кэшбэк и комбо — скидкой на чек. Подарок в ККТ — минимум 1 ₽.
 */
class FiscalDiscountTest {

    private fun d(s: String) = BigDecimal(s)
    private val now = 1_000_000L

    private fun card(freeAvailable: Int = 1, vouchers: List<ResolveVoucher> = emptyList(), reservationId: String? = "R-1") =
        CardInfo(
            code = "payload.sig", cardCode = "0012", paidCups = 3, cupsForFree = 5,
            freeAvailable = freeAvailable, cashbackKopecks = 5000,
            reservationId = reservationId, reservationExpiresAt = 0L, freeStatus = "RESERVED",
            vouchers = vouchers,
        )

    private fun line(
        uuid: String = "p1",
        name: String = "Латте",
        price: String = "250",
        qty: String = "1",
        eligible: Boolean = true,
        optionId: Int? = null,
    ) = CartLine(uuid, name, d(price), d(qty), freeEligible = eligible, optionId = optionId)

    private fun choice(free: Boolean = false, cashback: Boolean = false, kopecks: Long = 0L, voucher: Int? = null) =
        Choice.normalize(free, cashback, kopecks, voucher)

    // ── split ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `gift goes to positions, nothing to the receipt`() {
        val s = FiscalCalc.split(d("250"), listOf(d("250")), card(), choice(free = true), now)
        assertEquals(24900L, s.positionDiscountKopecks)
        assertEquals(0L, s.receiptDiscountKopecks)
        assertTrue(s.giftApplied)
    }

    @Test
    fun `cashback stays on the receipt and is never doubled`() {
        val s = FiscalCalc.split(d("400"), emptyList(), card(freeAvailable = 0), choice(cashback = true, kopecks = 5000L), now)
        assertEquals(0L, s.positionDiscountKopecks)
        assertEquals(5000L, s.receiptDiscountKopecks)
    }

    @Test
    fun `voucher goes to positions`() {
        val v = ResolveVoucher(id = 12, title = "15%", kind = "percent", value = 15)
        val s = FiscalCalc.split(d("400"), emptyList(), card(freeAvailable = 0, vouchers = listOf(v)), choice(voucher = 12), now)
        assertEquals(6000L, s.positionDiscountKopecks)
        assertEquals(0L, s.receiptDiscountKopecks)
        assertEquals(12, s.voucherId)
    }

    @Test
    fun `position discount keeps one rouble on the receipt`() {
        val v = ResolveVoucher(id = 13, title = "100%", kind = "percent", value = 100)
        val s = FiscalCalc.split(d("300"), emptyList(), card(freeAvailable = 0, vouchers = listOf(v)), choice(voucher = 13), now)
        assertEquals(29900L, s.positionDiscountKopecks)
        assertEquals(100L, d("300").toKopecks() - s.totalDiscountKopecks)
    }

    @Test
    fun `no card means no fiscal discount`() {
        val s = FiscalCalc.split(d("250"), listOf(d("250")), null, choice(free = true), now)
        assertEquals(0L, s.totalDiscountKopecks)
    }

    @Test
    fun `numeric code without reservation keeps full prices`() {
        val manual = card(reservationId = null).copy(code = "1234567890", freeStatus = "NONE")
        val s = FiscalCalc.split(d("250"), listOf(d("250")), manual, choice(free = true), now)
        assertEquals(0L, s.totalDiscountKopecks)
    }

    // ── цены позиций ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `gift unit becomes exactly one rouble`() {
        val lines = listOf(line())
        val split = FiscalCalc.split(d("250"), listOf(d("250")), card(), choice(free = true), now)
        val fiscal = FiscalLines.build(lines, split)
        val discounted = fiscal.single { it.unitPriceWithDiscount != null }
        assertEquals(d("1.00"), discounted.unitPriceWithDiscount)
        assertEquals(100L, FiscalLines.payableKopecks(fiscal))
    }

    @Test
    fun `no benefit leaves prices untouched`() {
        val lines = listOf(line())
        val split = FiscalCalc.split(d("250"), listOf(d("250")), card(), choice(), now)
        val fiscal = FiscalLines.build(lines, split)
        assertNull(fiscal.single().unitPriceWithDiscount)
        assertEquals(25000L, FiscalLines.payableKopecks(fiscal))
    }

    @Test
    fun `mixed cart keeps paid positions at full price`() {
        val lines = listOf(line(uuid = "coffee", price = "180"), line(uuid = "cake", price = "120", eligible = false))
        val split = FiscalCalc.split(d("300"), listOf(d("180")), card(), choice(free = true), now)
        val fiscal = FiscalLines.build(lines, split)
        assertEquals(12000L + 100L, FiscalLines.payableKopecks(fiscal))
    }

    @Test
    fun `two units of the same drink discount only the gifted one`() {
        val lines = listOf(line(price = "250", qty = "2"))
        val split = FiscalCalc.split(d("500"), listOf(d("250")), card(), choice(free = true), now)
        val fiscal = FiscalLines.build(lines, split)
        // Одна единица за 1 ₽, вторая по полной: 251 ₽.
        assertEquals(25100L, FiscalLines.payableKopecks(fiscal))
        assertEquals(2, fiscal.size)
    }

    @Test
    fun `toppings never become the gifted unit`() {
        val lines = listOf(line(uuid = "coffee", price = "250"), line(uuid = "syrup", price = "50", eligible = true, optionId = 7))
        val split = FiscalCalc.split(d("300"), listOf(d("250")), card(), choice(free = true), now)
        val fiscal = FiscalLines.build(lines, split)
        val discounted = fiscal.filter { it.unitPriceWithDiscount != null }
        assertEquals(1, discounted.size)
        assertEquals("coffee", discounted.single().productUuid)
    }

    @Test
    fun `voucher percent is spread over positions`() {
        val v = ResolveVoucher(id = 12, title = "15%", kind = "percent", value = 15)
        val lines = listOf(line(price = "250"), line(uuid = "cake", price = "150", eligible = false))
        val split = FiscalCalc.split(d("400"), emptyList(), card(freeAvailable = 0, vouchers = listOf(v)), choice(voucher = 12), now)
        val fiscal = FiscalLines.build(lines, split)
        assertEquals(40000L - 6000L, FiscalLines.payableKopecks(fiscal))
    }

    @Test
    fun `position discount never produces zero price`() {
        val v = ResolveVoucher(id = 13, title = "100%", kind = "percent", value = 100)
        val lines = listOf(line(price = "300"))
        val split = FiscalCalc.split(d("300"), emptyList(), card(freeAvailable = 0, vouchers = listOf(v)), choice(voucher = 13), now)
        val fiscal = FiscalLines.build(lines, split)
        assertEquals(100L, FiscalLines.payableKopecks(fiscal))
        assertTrue(fiscal.all { (it.unitPriceWithDiscount ?: it.unitPrice).signum() > 0 })
    }

    private fun BigDecimal.toKopecks() = DiscountCalc.kopecks(this)
}
