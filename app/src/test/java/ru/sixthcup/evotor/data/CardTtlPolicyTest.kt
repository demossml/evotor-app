package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** D-11: TTL не стирает карту посреди незавершённой продажи. */
class CardTtlPolicyTest {

    @Test
    fun `non empty cart keeps stale card`() {
        assertEquals(CardTtlPolicy.Action.KEEP_STALE, CardTtlPolicy.action(cartEmpty = false, pendingPayment = false))
    }

    @Test
    fun `pending payment keeps stale card`() {
        assertEquals(CardTtlPolicy.Action.KEEP_STALE, CardTtlPolicy.action(cartEmpty = true, pendingPayment = true))
    }

    @Test
    fun `idle checkout clears stale card`() {
        assertEquals(CardTtlPolicy.Action.CLEAR, CardTtlPolicy.action(cartEmpty = true, pendingPayment = false))
    }
}
