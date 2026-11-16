package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Окно клиента: что предлагать гостю. Главный случай — кэшбэк обязан оставаться доступным,
 * когда у гостя есть ваучеры (в кэшбэке лежат реферальные начисления).
 */
class ClientOffersTest {

    private val now = 1_000_000L
    private val voucher15 = ResolveVoucher(id = 12, title = "Скидка 15%", kind = "percent", value = 15)

    private fun card(
        code: String = "payload.sig",
        cardCode: String = "0012",
        paidCups: Int = 3,
        cupsForFree: Int = 5,
        freeAvailable: Int = 1,
        cashbackKopecks: Long = 5000,
        reservationId: String? = "R-1",
        reservationExpiresAt: Long = 0L,
        freeStatus: String = "RESERVED",
        vouchers: List<ResolveVoucher> = emptyList(),
        canRedeem: Boolean = true,
        identifiedBy: String = "QR",
    ) = CardInfo(
        code = code, cardCode = cardCode, paidCups = paidCups, cupsForFree = cupsForFree,
        freeAvailable = freeAvailable, cashbackKopecks = cashbackKopecks,
        reservationId = reservationId, reservationExpiresAt = reservationExpiresAt,
        freeStatus = freeStatus, vouchers = vouchers, canRedeem = canRedeem,
        identifiedBy = identifiedBy,
    )

    @Test
    fun `cashback stays available when the guest has vouchers`() {
        val offers = ClientOffers.of(card(vouchers = listOf(voucher15)), now)
        assertTrue(offers.cashback)
        assertEquals(1, offers.vouchers.size)
        assertTrue(offers.any)
    }

    @Test
    fun `cashback without vouchers`() {
        val offers = ClientOffers.of(card(), now)
        assertTrue(offers.cashback)
        assertTrue(offers.vouchers.isEmpty())
    }

    @Test
    fun `canRedeem false hides gift and cashback even with reservation`() {
        val offers = ClientOffers.of(card(canRedeem = false), now)
        assertFalse(offers.gift)
        assertFalse(offers.cashback)
    }

    @Test
    fun `no reservation disables gift cashback and vouchers`() {
        val offers = ClientOffers.of(
            card(reservationId = null, freeStatus = "NONE", vouchers = listOf(voucher15)), now,
        )
        assertFalse(offers.gift)
        assertFalse(offers.cashback)
        assertTrue(offers.vouchers.isEmpty())
    }

    @Test
    fun `canRedeem false hides vouchers`() {
        val offers = ClientOffers.of(card(vouchers = listOf(voucher15), canRedeem = false), now)
        assertTrue(offers.vouchers.isEmpty())
    }

    @Test
    fun `phone bonus cashback works without reservation`() {
        val offers = ClientOffers.of(
            card(
                code = "0012", reservationId = null, freeStatus = "NONE",
                canRedeem = true, identifiedBy = "PHONE",
            ),
            now,
        )
        assertTrue(offers.cashback)
        assertFalse(offers.gift)
        assertTrue(offers.vouchers.isEmpty())
    }

    @Test
    fun `manual code hides vouchers even with a reservation`() {
        val offers = ClientOffers.of(
            card(code = "0012", vouchers = listOf(voucher15), canRedeem = true), now,
        )
        assertTrue(offers.vouchers.isEmpty())
    }

    @Test
    fun `no free cup means no gift`() {
        assertFalse(ClientOffers.of(card(freeAvailable = 0), now).gift)
    }

    @Test
    fun `earned free cup means gift`() {
        assertTrue(ClientOffers.of(card(freeAvailable = 1), now).gift)
    }

    @Test
    fun `empty cashback balance hides cashback`() {
        assertFalse(ClientOffers.of(card(cashbackKopecks = 0), now).cashback)
    }

    @Test
    fun `any is false only when nothing can be offered`() {
        val none = ClientOffers.of(card(freeAvailable = 0, cashbackKopecks = 0), now)
        assertFalse(none.any)
        assertTrue(ClientOffers.of(card(freeAvailable = 0, vouchers = listOf(voucher15)), now).any)
        assertTrue(ClientOffers.of(card(freeAvailable = 0, cashbackKopecks = 3000), now).any)
    }

    @Test
    fun `reservation grace decides availability`() {
        val expired = card(reservationExpiresAt = now - ReservationPolicy.GRACE_SEC - 1)
        assertFalse(ClientOffers.of(expired, now).gift)
        assertFalse(ClientOffers.of(expired, now).cashback)

        val insideGrace = card(reservationExpiresAt = now - ReservationPolicy.GRACE_SEC + 1)
        assertTrue(ClientOffers.of(insideGrace, now).gift)
        assertTrue(ClientOffers.of(insideGrace, now).cashback)
    }
}
