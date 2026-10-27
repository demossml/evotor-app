package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перезапрос карты: чем спрашивать сервер и что делать с ответом.
 * После продажи карта должна обновляться сама, а не «залипать» до отвязки.
 */
class CardRefreshTest {

    private fun card(
        code: String = "0012",
        cardCode: String = "12",
        paidCups: Int = 3,
        freeAvailable: Int = 0,
        cashback: Long = 0,
        freeStatus: String = "NONE",
        reservationId: String? = null,
    ) = CardInfo(
        code = code,
        cardCode = cardCode,
        paidCups = paidCups,
        cupsForFree = 5,
        freeAvailable = freeAvailable,
        cashbackKopecks = cashback,
        reservationId = reservationId,
        reservationExpiresAt = 0L,
        freeStatus = freeStatus,
    )

    @Test
    fun `qr card is refreshed by its signed token`() {
        assertEquals("payload.sig", CardRefresh.credential(card(code = "payload.sig", cardCode = "12")))
    }

    @Test
    fun `manual card is refreshed by the canonical numeric code`() {
        assertEquals("12", CardRefresh.credential(card(code = "000 12", cardCode = "12")))
        // Код карты ещё не пришёл от сервера — используем введённое значение.
        assertEquals("777", CardRefresh.credential(card(code = "777", cardCode = "")))
    }

    @Test
    fun `nothing to refresh without a credential`() {
        assertNull(CardRefresh.credential(card(code = "", cardCode = "")))
    }

    @Test
    fun `successful refresh replaces the session`() {
        val fresh = card(paidCups = 4, freeAvailable = 0)
        val r = CardRefresh.apply(card(paidCups = 3), fresh)
        assertEquals(RefreshOutcome.UPDATED, r.outcome)
        assertEquals(fresh, r.info)
        assertTrue(r.updated)
    }

    @Test
    fun `rejected card drops the session`() {
        val r = CardRefresh.apply(card(), card(code = "", cardCode = "", freeStatus = "ERROR"))
        assertEquals(RefreshOutcome.DROPPED, r.outcome)
        assertNull(r.info)
        assertTrue(r.dropped)
    }

    @Test
    fun `offline keeps previous data and never drops the card`() {
        val current = card(paidCups = 3, cashback = 900)
        val r = CardRefresh.apply(current, card(code = "", cardCode = "", freeStatus = "OFFLINE"))
        assertEquals(RefreshOutcome.KEPT_STALE, r.outcome)
        assertEquals(current, r.info)
        assertFalse(r.updated)
    }

    @Test
    fun `only real changes are worth a toast`() {
        val current = card(paidCups = 3)
        assertFalse(CardRefresh.isNotable(current, card(paidCups = 3)))
        assertTrue(CardRefresh.isNotable(current, card(paidCups = 4)))
        assertTrue(CardRefresh.isNotable(current, card(paidCups = 3, freeAvailable = 1)))
        assertTrue(CardRefresh.isNotable(current, card(paidCups = 3, cashback = 500)))
    }
}
