package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Публичный номер заказа: формат печати, разбор ответа сервера и пригодность номера.
 * Номер выдаёт только backend — локально ничего не генерируется.
 */
class QueueNumberTest {

    @Test
    fun `print text is human readable and has no fiscal fields`() {
        assertEquals("ЗАКАЗ № 47", QueueNumber.printTextFor("47"))
        assertEquals("ЗАКАЗ № 103", QueueNumber.printTextFor("103"))
    }

    @Test
    fun `parses a successful backend response`() {
        val n = QueueNumber.parse(
            """{ "ok": true, "orderId": "ord-1", "publicOrderNumber": "47",
                "periodKey": "2026-10-27", "expiresAt": 1999999999000 }""",
        )
        assertEquals("ord-1", n?.orderId)
        assertEquals("47", n?.publicNumber)
        assertEquals("2026-10-27", n?.periodKey)
        assertEquals("ЗАКАЗ № 47", n?.printText)
    }

    @Test
    fun `rejects broken or non numeric responses`() {
        assertNull(QueueNumber.parse("""{ "ok": false }"""))
        assertNull(QueueNumber.parse("""{ "ok": true, "orderId": "o", "publicOrderNumber": "4a" }"""))
        assertNull(QueueNumber.parse("""{ "ok": true, "orderId": "", "publicOrderNumber": "4" }"""))
        assertNull(QueueNumber.parse("not json"))
    }

    @Test
    fun `number is usable only while fresh`() {
        val now = 1_000_000L
        val fresh = QueueNumber(orderId = "o", publicNumber = "5", issuedAt = now - 1_000L)
        assertTrue(QueueNumber.isUsable(fresh, now))
        val old = QueueNumber(orderId = "o", publicNumber = "5", issuedAt = now - QueueNumber.MAX_AGE_MS - 1)
        assertFalse(QueueNumber.isUsable(old, now))
        val expired = QueueNumber(orderId = "o", publicNumber = "5", issuedAt = now, expiresAt = now - 1)
        assertFalse(QueueNumber.isUsable(expired, now))
        assertFalse(QueueNumber.isUsable(null, now))
        assertFalse(QueueNumber.isUsable(QueueNumber("o", " ", issuedAt = now), now))
    }

    @Test
    fun `server number wins over the local counter on confirm`() {
        // Логика подтверждения: переданный серверный номер используется как есть.
        val pending = QueueOrder(
            number = 0,
            createdAt = 1_000L,
            items = listOf("Капучино"),
            totalRub = java.math.BigDecimal("200"),
            status = OrderStatus.PENDING,
            id = "srv",
        )
        val confirmed = QueueOps.confirm(listOf(pending), "srv", 47, now = 2_000L)
        assertEquals(47, confirmed.single().number)
        assertEquals("47", confirmed.single().label)
    }
}
