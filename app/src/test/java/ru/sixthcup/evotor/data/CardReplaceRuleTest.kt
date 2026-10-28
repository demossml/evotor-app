package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Замена карты — только когда гость реально другой. */
class CardReplaceRuleTest {

    private fun card(code: String) = CardInfo(
        code = code, cardCode = "0012", paidCups = 1, cupsForFree = 5, freeAvailable = 0,
        cashbackKopecks = 0, reservationId = null, reservationExpiresAt = 0L, freeStatus = "NONE",
    )

    @Test
    fun `same qr token is not a replacement`() {
        assertFalse(CardReplaceRule.shouldReplaceCard(card("payload.sig"), "payload.sig"))
    }

    @Test
    fun `same numeric code with spaces and prefix is the same card`() {
        val current = card("12")
        assertFalse(CardReplaceRule.shouldReplaceCard(current, " 0012 "))
        assertFalse(CardReplaceRule.shouldReplaceCard(current, "6.7:0012"))
        assertFalse(CardReplaceRule.shouldReplaceCard(current, "67coffee:12"))
    }

    @Test
    fun `different code replaces the session`() {
        assertTrue(CardReplaceRule.shouldReplaceCard(card("payload.sig"), "payload.other"))
        assertTrue(CardReplaceRule.shouldReplaceCard(card("12"), "34"))
    }

    @Test
    fun `no bound card means nothing to replace`() {
        assertFalse(CardReplaceRule.shouldReplaceCard(null, "payload.sig"))
    }

    @Test
    fun `empty incoming code never replaces`() {
        assertFalse(CardReplaceRule.shouldReplaceCard(card("payload.sig"), "   "))
        assertFalse(CardReplaceRule.shouldReplaceCard(card(""), "payload.sig"))
    }

    @Test
    fun `normalize strips prefix and case`() {
        assertEquals("12", CardReplaceRule.normalize(" 6.7:0012 "))
        assertEquals("payload.sig", CardReplaceRule.normalize("Payload.Sig"))
    }
}
