package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сессия «подтянутого предзаказа»: пока она активна, успешная продажа должна пометить заказ
 * оплаченным; после отчёта — очищаться.
 */
class PreorderPullSessionTest {

    @Test
    fun `set then read`() {
        val s = PreorderPullSession
        s.clear()
        s.set(
            orderId = "ord-1",
            shortCode = "762341",
            items = listOf(PreorderPullSession.Item("U1", "Капучино", 2)),
        )
        assertTrue(s.active())
        assertEquals("ord-1", s.orderId())
        assertEquals("762341", s.shortCode())
        assertEquals(1, s.items().size)
        assertEquals(2, s.items().first().quantity)
    }

    @Test
    fun `clear resets everything`() {
        val s = PreorderPullSession
        s.set("ord-2", null, listOf(PreorderPullSession.Item("U1", "Капучино", 1)))
        s.clear()
        assertFalse(s.active())
        assertNull(s.orderId())
        assertNull(s.shortCode())
        assertTrue(s.items().isEmpty())
    }

    @Test
    fun `blank order id is not active`() {
        val s = PreorderPullSession
        s.set("   ", null, emptyList())
        assertFalse(s.active())
        assertNull(s.orderId())
        s.clear()
    }
}
