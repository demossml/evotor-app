package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B4: списание возможно только по безопасному proof.
 * Числовой код карты может показать прогресс, но не даёт free/cashback даже если backend
 * ошибочно прислал reservationId/canRedeem.
 */
class LoyaltyProofTest {

    private fun card(
        code: String,
        kind: String? = null,
        identifiedBy: String = "QR",
        canRedeem: Boolean = true,
        reservationId: String? = "R-1",
    ) = CardInfo(
        code = code,
        cardCode = "12",
        paidCups = 0,
        cupsForFree = 5,
        freeAvailable = 1,
        cashbackKopecks = 5000,
        balanceKopecks = 5000,
        reservationId = reservationId,
        reservationExpiresAt = 9_999_999_999L,
        freeStatus = "RESERVED",
        canRedeem = canRedeem,
        kind = kind,
        identifiedBy = identifiedBy,
    )

    @Test
    fun `numeric card code cannot spend free or cashback even with a reservation`() {
        val info = card(code = "0012", kind = "code", identifiedBy = "CODE")
        val sc = CardSession.sc(info, freeApplied = true, cashbackKopecks = 3000)
        assertEquals(0, sc.getInt("free"))
        assertEquals(0L, sc.getLong("cb"))
        assertFalse(CardSession.cashbackRedeemable(info, reserved = true))
        assertFalse(CardProgress.of(info).redeemEnabled)
    }

    @Test
    fun `qr token with live reservation can spend`() {
        val info = card(code = "payload.signature", kind = "token", identifiedBy = "QR")
        val sc = CardSession.sc(info, freeApplied = true, cashbackKopecks = 3000)
        assertEquals(1, sc.getInt("free"))
        assertEquals(3000L, sc.getLong("cb"))
        assertTrue(CardSession.cashbackRedeemable(info, reserved = true))
    }

    @Test
    fun `phone bonus card may spend cashback but not free gift`() {
        val info = card(
            code = "0012",
            kind = "code",
            identifiedBy = "PHONE",
            reservationId = null,
        )
        val sc = CardSession.sc(info, freeApplied = true, cashbackKopecks = 3000)
        assertEquals(0, sc.getInt("free"))
        assertEquals(3000L, sc.getLong("cb"))
        assertTrue(CardSession.cashbackRedeemable(info, reserved = false))
    }

    @Test
    fun `server refusal disables all spending`() {
        val info = card(code = "payload.signature", kind = "token", identifiedBy = "QR", canRedeem = false)
        assertFalse(CardSession.cashbackRedeemable(info, reserved = true))
        assertFalse(CardSession.extras(info, freeApplied = true, cashbackKopecks = 3000).getJSONObject("sc").getInt("free") > 0)
    }
}
