package ru.sixthcup.evotor.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Ваучеры на кассе: парсер, правила скидки и запись в sc. */
class VoucherTest {

    private val percent = ResolveVoucher(id = 1, title = "Скидка 10%", kind = "percent", value = 10)
    private val fixed = ResolveVoucher(id = 2, title = "Скидка 50 ₽", kind = "fixed", value = 5000)
    private val noStack = ResolveVoucher(id = 3, title = "Не с подарком", kind = "percent", value = 20, stackableWithFreeCup = false)

    @Test
    fun `resolve without vouchers stays empty`() {
        assertTrue(VoucherJson.parse(null).isEmpty())
        assertTrue(VoucherJson.parse(JSONArray("[]")).isEmpty())
    }

    @Test
    fun `parses a percent voucher`() {
        val arr = JSONArray("""[ { "id": 5, "title": "10%", "kind": "percent", "value": 10 } ]""")
        val v = VoucherJson.parse(arr).single()
        assertEquals(5, v.id)
        assertEquals("10%", v.title)
        assertTrue(v.isPercent)
        assertTrue(v.stackableWithFreeCup) // по умолчанию суммируется с подарком
    }

    @Test
    fun `garbage entries are skipped, not thrown`() {
        val arr = JSONArray("""[ { "id": 0, "title": "x", "kind": "percent", "value": 10 },
                                  { "id": 7, "title": "", "kind": "percent", "value": 10 },
                                  { "id": 8, "title": "ok", "kind": "unknown", "value": 10 },
                                  { "id": 9, "title": "fixed", "kind": "fixed", "value": 5000 } ]""")
        val list = VoucherJson.parse(arr)
        assertEquals(1, list.size)
        assertEquals(9, list.single().id)
    }

    @Test
    fun `percent is taken from the amount left after the free cup`() {
        // Чек 500, подарок 200 → остаток 300, 10% = 30.
        assertEquals(BigDecimal("30.00"), VoucherCalc.discount(BigDecimal("300.00"), percent))
    }

    @Test
    fun `fixed kopecks are capped by the remainder`() {
        assertEquals(BigDecimal("50.00"), VoucherCalc.discount(BigDecimal("300.00"), fixed))
        assertEquals(BigDecimal("20.00"), VoucherCalc.discount(BigDecimal("20.00"), fixed))
        assertEquals(0, VoucherCalc.discount(BigDecimal.ZERO, fixed).compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `stackability decides whether the voucher may be used with a gift`() {
        assertTrue(VoucherCalc.allowedTogether(percent, freeChosen = true))
        assertTrue(VoucherCalc.allowedTogether(percent, freeChosen = false))
        assertFalse(VoucherCalc.allowedTogether(noStack, freeChosen = true))
        assertTrue(VoucherCalc.allowedTogether(noStack, freeChosen = false))
        assertTrue(VoucherCalc.allowedTogether(null, freeChosen = true))
    }

    @Test
    fun `discount never exceeds the check`() {
        val total = VoucherCalc.discount(BigDecimal("100.00"), ResolveVoucher(1, "x", "fixed", 50000))
        assertEquals(BigDecimal("100.00"), total)
    }

    private fun card(reservationId: String?) = CardInfo(
        code = if (reservationId != null) "payload.sig" else "0012",
        cardCode = "12",
        paidCups = 1,
        cupsForFree = 5,
        freeAvailable = 0,
        cashbackKopecks = 0,
        reservationId = reservationId,
        reservationExpiresAt = 0L,
        freeStatus = if (reservationId != null) "RESERVED" else "NONE",
        vouchers = listOf(percent),
    )

    @Test
    fun `voucherId is written only with a live reservation`() {
        val withReserve = CardSession.sc(card("R-1"), freeApplied = false, cashbackKopecks = 0, voucherId = 1)
        assertEquals(1, withReserve.getInt("voucherId"))

        val manual = CardSession.sc(card(null), freeApplied = false, cashbackKopecks = 0, voucherId = 1)
        assertFalse(manual.has("voucherId"))
        // остальные поля не пострадали
        assertEquals("12", manual.getString("c"))
        assertEquals(2, manual.getInt("v"))
    }

    @Test
    fun `labels show percent as percent and fixed as rubles from kopecks`() {
        assertEquals("10%", VoucherCalc.amountLabel(percent))
        assertEquals("50 ₽", VoucherCalc.amountLabel(fixed))
        assertEquals("Скидка 50 ₽ −50 ₽", VoucherCalc.label(fixed))
        assertEquals("300 ₽", VoucherCalc.amountLabel(ResolveVoucher(3, "t", "fixed", 30000)))
    }

    @Test
    fun `image url is parsed but ignored by the logic`() {
        val v = VoucherJson.parse(
            JSONArray("""[ { "id": 4, "title": "t", "kind": "percent", "value": 5, "imageUrl": "https://x/y.png", "unknown": 1 } ]"""),
        ).single()
        assertEquals("https://x/y.png", v.imageUrl)
        assertEquals(BigDecimal("5.00"), VoucherCalc.discount(BigDecimal("100"), v))
    }

    @Test
    fun `vouchers survive the session round trip`() {
        val json = VoucherJson.toJson(listOf(percent, noStack))
        val back = VoucherJson.parse(json)
        assertEquals(2, back.size)
        assertEquals("Скидка 10%", back.first().title)
        assertFalse(back.last().stackableWithFreeCup)
    }
}
