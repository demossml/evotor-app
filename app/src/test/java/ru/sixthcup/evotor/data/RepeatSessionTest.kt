package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Очередь «Повторить»: одна позиция за раз. Ошибка/закрытие окна не продвигают очередь,
 * ручное добавление другого товара её не трогает, остаток можно отменить явно.
 */
class RepeatSessionTest {

    private fun product(uuid: String) = CatalogProduct(
        uuid = uuid, name = "Товар $uuid", priceRub = BigDecimal("100"), allowToSell = true,
        recipe = null, description = null, article = null,
    )

    private val a = product("A")
    private val b = product("B")
    private val c = product("C")

    @Test
    fun `start returns the first item and keeps the rest`() {
        val session = RepeatSession()
        assertEquals(a, session.start(listOf(a, b, c)))
        assertEquals(a, session.pending)
        assertEquals(2, session.remaining)
        assertTrue(session.active)
        assertFalse(session.incomplete)
    }

    @Test
    fun `next item opens only after the current one is added`() {
        val session = RepeatSession()
        session.start(listOf(a, b))
        assertEquals(b, session.added(a))
        assertEquals(b, session.pending)
        assertEquals(1, session.completed)
        assertNull(session.added(b))
        assertNull(session.pending)
        assertFalse(session.active)
        assertEquals(2, session.completed)
    }

    @Test
    fun `closing the sheet does not advance the queue`() {
        val session = RepeatSession()
        session.start(listOf(a, b))
        assertTrue(session.closedWithoutAdd(a))
        assertNull("ошибка/закрытие не продвигает очередь", session.pending)
        assertEquals("остаток сохранён", 1, session.remaining)
        assertTrue(session.incomplete)
        assertEquals(0, session.completed)
    }

    @Test
    fun `resume continues the interrupted repeat`() {
        val session = RepeatSession()
        session.start(listOf(a, b))
        session.closedWithoutAdd(a)
        assertEquals(b, session.resume())
        assertEquals(b, session.pending)
    }

    @Test
    fun `cancel drops the remainder and reports how many were lost`() {
        val session = RepeatSession()
        session.start(listOf(a, b, c))
        session.added(a)
        assertEquals("открытая + ожидающие", 2, session.cancel())
        assertNull(session.pending)
        assertEquals(0, session.remaining)
        assertFalse(session.active)
        assertFalse(session.incomplete)
    }

    @Test
    fun `restart does not mix the new queue with the old one`() {
        val session = RepeatSession()
        session.start(listOf(a, b, c))
        session.added(a)
        assertEquals("новая очередь начинается с первой своей позиции", c, session.start(listOf(c)))
        assertEquals(c, session.pending)
        assertEquals(0, session.remaining)
        assertEquals(0, session.completed)
    }

    @Test
    fun `manual add of another product neither advances nor clears the queue`() {
        val session = RepeatSession()
        session.start(listOf(a, b))
        assertNull(session.added(product("MANUAL")))
        assertFalse(session.closedWithoutAdd(product("MANUAL")))
        assertEquals("очередь на месте", a, session.pending)
        assertEquals(1, session.remaining)
        assertFalse(session.incomplete)
    }

    @Test
    fun `closed sheet of a manual product does not mark the repeat incomplete`() {
        val session = RepeatSession()
        session.start(listOf(a))
        assertFalse(session.closedWithoutAdd(product("MANUAL")))
        assertFalse(session.incomplete)
        assertEquals(a, session.pending)
    }

    @Test
    fun `empty plan leaves no active queue`() {
        val session = RepeatSession()
        session.start(listOf(a))
        session.cancel()
        assertNull(session.start(emptyList()))
        assertFalse(session.active)
        assertNull(session.added(a))
    }
}
