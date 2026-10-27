package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * Номер заказа для бариста: суточный счётчик, один номер на заказ.
 * Правило сброса уже живёт в [OrderNumbers] (эпоха = номер смены Эвотора, иначе календарный день);
 * здесь фиксируем это отдельными кейсами.
 */
class OrderNumberDailyTest {

    @Test
    fun `next order takes the next number of the day`() {
        val (first, c1) = OrderNumbers.next(NumberCounter(null, 0), "d2026-10-27", emptySet())
        val (second, c2) = OrderNumbers.next(c1, "d2026-10-27", emptySet())
        val (third, _) = OrderNumbers.next(c2, "d2026-10-27", emptySet())
        assertEquals(1, first)
        assertEquals(2, second)
        assertEquals(3, third)
    }

    @Test
    fun `new day starts from one again`() {
        val (_, evening) = OrderNumbers.next(NumberCounter("d2026-10-27", 42), "d2026-10-27", emptySet())
        val (morning, counter) = OrderNumbers.next(evening, "d2026-10-28", emptySet())
        assertEquals(1, morning)
        assertEquals("d2026-10-28", counter.key)
    }

    @Test
    fun `new shift restarts numbering even within one day`() {
        val (_, first) = OrderNumbers.next(NumberCounter(null, 0), "s1", emptySet())
        val (next, _) = OrderNumbers.next(first, "s2", emptySet())
        assertEquals(1, next)
    }

    @Test
    fun `confirming twice does not take a second number`() {
        val pending = QueueOrder(
            number = 0,
            createdAt = 1_000L,
            items = listOf("Капучино"),
            totalRub = BigDecimal("200"),
            status = OrderStatus.PENDING,
            id = "abc",
        )
        val confirmed = QueueOps.confirm(listOf(pending), "abc", 7, now = 2_000L)
        assertEquals(7, confirmed.single().number)
        // Повторный сигнал (ещё один onResume) не должен менять номер.
        val again = QueueOps.confirm(confirmed, "abc", 8, now = 3_000L)
        assertEquals(7, again.single().number)
        assertEquals(OrderStatus.CONFIRMED, again.single().status)
    }

    @Test
    fun `cancelled pending does not consume the number`() {
        val pending = QueueOrder(
            number = 0,
            createdAt = 1_000L,
            items = listOf("Латте"),
            totalRub = BigDecimal("190"),
            status = OrderStatus.PENDING,
            id = "x",
        )
        val afterCancel = QueueOps.cancelPending(listOf(pending), "x")
        assertEquals(emptyList<QueueOrder>(), afterCancel)
        // Счётчик не двигался: следующий заказ получает 1, а не 2.
        val (next, _) = OrderNumbers.next(NumberCounter("d2026-10-27", 0), "d2026-10-27", QueueOps.activeNumbers(afterCancel))
        assertEquals(1, next)
    }

    @Test
    fun `number is unique among active orders`() {
        val pending = QueueOrder(0, 1L, listOf("Раф"), BigDecimal("250"), OrderStatus.PENDING, id = "two")
        val active = setOf(1)
        val (n, _) = OrderNumbers.next(NumberCounter("d2026-10-27", 0), "d2026-10-27", active)
        assertNotEquals(1, n)
        assertEquals(2, n)
        assertEquals(0, pending.number)
    }
}
