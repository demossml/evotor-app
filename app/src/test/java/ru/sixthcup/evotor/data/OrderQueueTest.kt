package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class OrderQueueTest {
    private fun order(n: Int, at: Long = 0, st: OrderStatus = OrderStatus.PREPARING, rt: Long? = null) =
        QueueOrder(n, at, listOf("Капучино"), BigDecimal("250"), st, rt)

    @Test fun epochPrefersSessionNumberElseDay() {
        assertEquals("s42", OrderNumbers.epochKey(42L, "2026-10-19"))
        assertEquals("d2026-10-19", OrderNumbers.epochKey(null, "2026-10-19"))
    }

    @Test fun numbersIncrementWithinEpoch() {
        val (a, c1) = OrderNumbers.next(NumberCounter(null, 0), "s1", emptySet())
        val (b, _) = OrderNumbers.next(c1, "s1", emptySet())
        assertEquals(1, a); assertEquals(2, b)
    }

    @Test fun numbersResetOnNewEpoch() {
        val (n, c) = OrderNumbers.next(NumberCounter("s1", 37), "s2", emptySet())
        assertEquals(1, n); assertEquals("s2", c.key)
    }

    @Test fun wrapsAfterMaxAndSkipsActive() {
        val (n, _) = OrderNumbers.next(NumberCounter("s1", 99), "s1", setOf(1, 2))
        assertEquals(3, n)
    }

    @Test fun formatPadsToTwoDigits() {
        assertEquals("07", OrderNumbers.format(7)); assertEquals("42", OrderNumbers.format(42))
    }

    @Test fun markReadyAndReopen() {
        val l = listOf(order(1), order(2))
        val r = QueueOps.markReady(l, 1, 1000)
        assertEquals(OrderStatus.READY, r.first { it.number == 1 }.status)
        assertEquals(1000L, r.first { it.number == 1 }.readyAt)
        assertEquals(OrderStatus.PREPARING, r.first { it.number == 2 }.status)
        val back = QueueOps.reopen(r, 1)
        assertEquals(OrderStatus.PREPARING, back.first { it.number == 1 }.status)
        assertNull(back.first { it.number == 1 }.readyAt)
    }

    @Test fun markReadyUnknownIsNoop() {
        val l = listOf(order(1))
        assertEquals(l, QueueOps.markReady(l, 5, 1))
    }

    @Test fun addReplacesSameNumber() {
        val l = QueueOps.add(listOf(order(3, at = 1)), order(3, at = 2))
        assertEquals(1, l.size); assertEquals(2L, l[0].createdAt)
    }

    @Test fun pruneDropsHandedAndStale() {
        val now = 100L * 60_000L * 60
        val fresh = order(1, at = now - 1000)
        val handed = order(2, at = now - 3_600_000, st = OrderStatus.READY, rt = now - QueueOps.READY_TTL_MS - 1)
        val justReady = order(3, at = now - 3_600_000, st = OrderStatus.READY, rt = now - 1000)
        val stale = order(4, at = now - QueueOps.MAX_AGE_MS - 1)
        val kept = QueueOps.prune(listOf(fresh, handed, justReady, stale), now).map { it.number }
        assertEquals(listOf(1, 3), kept)
    }

    @Test fun preparingSortedOldestFirstReadyNewestFirst() {
        val l = listOf(order(2, at = 20), order(1, at = 10), order(3, 5, OrderStatus.READY, 50), order(4, 6, OrderStatus.READY, 60))
        assertEquals(listOf(1, 2), QueueOps.preparing(l).map { it.number })
        assertEquals(listOf(4, 3), QueueOps.ready(l).map { it.number })
    }

    @Test fun tallyResetsOnEpochChange() {
        val t = ShiftTally(null, 0, BigDecimal.ZERO).add("s1", BigDecimal("300")).add("s1", BigDecimal("200"))
        assertEquals(2, t.receipts); assertEquals(BigDecimal("500"), t.sumRub); assertEquals(BigDecimal("250"), t.avgRub)
        val n = t.add("s2", BigDecimal("100"))
        assertEquals(1, n.receipts); assertEquals(BigDecimal("100"), n.sumRub)
        assertEquals(0, t.forEpoch("s9").receipts)
        assertTrue(t.forEpoch("s9").avgRub == null)
    }
}
