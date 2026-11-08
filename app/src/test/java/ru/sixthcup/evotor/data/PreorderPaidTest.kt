package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-6: повтор mark-paid только на сеть/5xx, ограниченное число раз, и только по распечатанному
 * чеку продажи (аннулированный чек печати не даёт).
 */
class PreorderPaidTest {

    @Test
    fun `network and server errors are retryable`() {
        assertTrue(PreorderPaid.retryable("network"))
        assertTrue(PreorderPaid.retryable("timeout"))
        assertTrue(PreorderPaid.retryable("http_500"))
        assertTrue(PreorderPaid.retryable("http_503"))
        assertTrue(PreorderPaid.retryable("http_429"))
        assertTrue(PreorderPaid.retryable("http_408"))
        assertTrue(PreorderPaid.retryable("internal_error"))
    }

    @Test
    fun `business errors are final and not retried`() {
        assertFalse(PreorderPaid.retryable("benefit_conflict"))
        assertFalse(PreorderPaid.retryable("http_409"))
        assertFalse(PreorderPaid.retryable("http_400"))
        assertFalse(PreorderPaid.retryable("http_401"))
        assertFalse(PreorderPaid.retryable("http_403"))
        assertFalse(PreorderPaid.retryable("http_404"))
        assertFalse(PreorderPaid.retryable("invalid_transition"))
        assertFalse(PreorderPaid.retryable(null))
        assertFalse(PreorderPaid.retryable(""))
    }

    @Test
    fun `retry delays grow and the last attempt does not wait`() {
        assertEquals(1_500L, PreorderPaid.delayMs(1))
        assertEquals(4_000L, PreorderPaid.delayMs(2))
        assertEquals(0L, PreorderPaid.delayMs(PreorderPaid.MAX_ATTEMPTS))
    }

    @Test
    fun `paid is reported only for a printed sale with a known order`() {
        assertTrue(PreorderPaid.shouldMarkSalePaid(salePrinted = true, preorderId = "order-1"))
        assertFalse("аннул не печатается — paid не отправляем", PreorderPaid.shouldMarkSalePaid(false, "order-1"))
        assertFalse(PreorderPaid.shouldMarkSalePaid(true, null))
        assertFalse(PreorderPaid.shouldMarkSalePaid(true, "  "))
    }
}
