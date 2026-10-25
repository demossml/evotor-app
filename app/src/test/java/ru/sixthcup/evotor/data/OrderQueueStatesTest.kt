package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Модель состояний очереди бариста:
 * PENDING (чек открыт) → CONFIRMED (оплата прошла, номер присвоен) → READY,
 * а также отмена PENDING (чек не закрылся) и EXPIRED по TTL.
 */
class OrderQueueStatesTest {

    private val base = 1_700_000_000_000L

    private fun pending(id: String, at: Long = base) =
        QueueOrder(0, at, listOf("Капучино"), BigDecimal("250"), OrderStatus.PENDING, null, id)

    private fun confirmed(n: Int, id: String, at: Long = base) =
        QueueOrder(n, at, listOf("Латте"), BigDecimal("300"), OrderStatus.CONFIRMED, null, id, at)

    @Test
    fun `confirm assigns number once and is idempotent`() {
        val pending = listOf(pending("p1"))
        val once = QueueOps.confirm(pending, "p1", 1, base + 5_000)
        assertEquals(OrderStatus.CONFIRMED, once.first().status)
        assertEquals(1, once.first().number)
        assertEquals(base + 5_000, once.first().confirmedAt)
        assertFalse(QueueOps.hasPending(once, "p1"))

        // Повторный сигнал подтверждения (onResume вызван ещё раз) ничего не меняет.
        val twice = QueueOps.confirm(once, "p1", 2, base + 9_000)
        assertEquals(once, twice)
        assertEquals(1, twice.first().number)
    }

    @Test
    fun `confirm of unknown id changes nothing`() {
        val list = listOf(pending("p1"))
        assertEquals(list, QueueOps.confirm(list, "nope", 1, base))
    }

    @Test
    fun `pending ttl expires at the boundary and not one millisecond earlier`() {
        val order = pending("p1")
        val justBefore = QueueOps.expire(listOf(order), base + QueueOps.PENDING_TTL_MS - 1)
        assertEquals(OrderStatus.PENDING, justBefore.first().status)

        val exactly = QueueOps.expire(listOf(order), base + QueueOps.PENDING_TTL_MS)
        assertEquals(OrderStatus.EXPIRED, exactly.first().status)

        val after = QueueOps.expire(listOf(order), base + QueueOps.PENDING_TTL_MS + 1)
        assertEquals(OrderStatus.EXPIRED, after.first().status)

        // EXPIRED не висит в очереди после очистки.
        assertTrue(QueueOps.prune(after, base + QueueOps.PENDING_TTL_MS + 1).isEmpty())
    }

    @Test
    fun `cancel removes the pending record and does not consume a number`() {
        val list = listOf(pending("p1"))
        val cancelled = QueueOps.cancelPending(list, "p1")
        assertTrue(cancelled.isEmpty())
        assertTrue(QueueOps.activeNumbers(cancelled).isEmpty())

        // Отмена не сдвигает нумерацию: первый подтверждённый заказ получает 01.
        val (n, counter) = OrderNumbers.next(NumberCounter(null, 0), "s1", QueueOps.activeNumbers(cancelled))
        assertEquals(1, n)
        assertEquals(1, counter.last)

        val afterFirst = QueueOps.confirm(cancelled, "p2", n, base)
        val (second, _) = OrderNumbers.next(counter, "s1", QueueOps.activeNumbers(afterFirst))
        assertEquals(2, second)
    }

    @Test
    fun `expired pending never shows up for barista nor consumes a number`() {
        val expired = QueueOps.expire(listOf(pending("p1")), base + QueueOps.PENDING_TTL_MS)
        assertTrue(QueueOps.preparing(expired, showPending = true).isEmpty())
        assertTrue(QueueOps.activeNumbers(expired).isEmpty())
        assertFalse(QueueOps.hasPending(expired, "p1"))
    }

    @Test
    fun `tally counts confirmed and ready only`() {
        val list = listOf(
            pending("p-pending", at = base + 1),
            confirmed(1, "c1"),
            confirmed(2, "c2").copy(status = OrderStatus.READY, readyAt = base),
            pending("p-expired", at = base - 1_000_000).copy(status = OrderStatus.EXPIRED),
        )
        val countable = QueueOps.countable(list).map { it.id }
        assertEquals(listOf("c1", "c2"), countable)
        assertFalse(countable.contains("p-pending"))
        assertFalse(countable.contains("p-expired"))
    }

    @Test
    fun `barista sees only confirmed unless the store opted in to pending`() {
        val list = listOf(pending("p1"), confirmed(1, "c1"))
        assertEquals(listOf(1), QueueOps.preparing(list).map { it.number })
        assertEquals(2, QueueOps.preparing(list, showPending = true).size)
        assertEquals("—", list.first().label)
    }
}
