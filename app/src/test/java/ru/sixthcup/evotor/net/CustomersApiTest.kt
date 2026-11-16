package ru.sixthcup.evotor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomersApiTest {

    @Test
    fun `parses flat found profile`() {
        val p = CustomersApi.parse(
            """{"ok":true,"found":true,"cardCode":"0007","cardId":"c-1","balanceKopecks":1250,
                "effectiveEarnPercent":5,"earnSource":"base","phoneMasked":"+7 ••• ••• 45 67",
                "created":false}""",
        )
        assertTrue(p.ok)
        assertTrue(p.found)
        assertEquals("0007", p.cardCode)
        assertEquals("c-1", p.cardId)
        assertEquals(1250L, p.balanceKopecks)
        assertEquals(5, p.effectiveEarnPercent)
        assertEquals("base", p.earnSource)
        assertEquals("+7 ••• ••• 45 67", p.phoneMasked)
    }

    @Test
    fun `parses nested profile and aliases`() {
        val p = CustomersApi.parse(
            """{"ok":true,"profile":{"found":true,"card_code":"42","balance":900,
                "earnPercent":7,"phone_masked":"+7 ••• ••• 00 42"}}""",
        )
        assertTrue(p.found)
        assertEquals("42", p.cardCode)
        assertEquals(900L, p.balanceKopecks)
        assertEquals(7, p.effectiveEarnPercent)
    }

    @Test
    fun `not found is a valid answer`() {
        val p = CustomersApi.parse("""{"ok":true,"found":false}""")
        assertTrue(p.ok)
        assertFalse(p.found)
        assertEquals(0L, p.balanceKopecks)
    }

    @Test
    fun `rate limit and service errors become readable`() {
        val limited = CustomersApi.parse("""{"ok":false,"error":"rate_limited"}""", 429)
        assertFalse(limited.ok)
        assertTrue(CustomersApi.errorText(429, limited.error).contains("Подождите"))
        assertTrue(CustomersApi.errorText(503, null).contains("временно недоступен"))
    }

    @Test
    fun `invalid phone is rejected before network`() {
        val p = CustomersApi.lookup("123")
        assertFalse(p.ok)
        assertNotNull(p.error)
        assertEquals("invalid_phone", p.error)
    }
}
