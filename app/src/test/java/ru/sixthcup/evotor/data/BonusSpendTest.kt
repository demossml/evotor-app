package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BonusSpendTest {

    @Test
    fun `max is limited by balance`() {
        assertEquals(5000L, BonusSpend.maxSpendKopecks(balanceKopecks = 5000, cartKopecks = 25000))
    }

    @Test
    fun `max is limited by cart`() {
        assertEquals(25000L, BonusSpend.maxSpendKopecks(balanceKopecks = 50000, cartKopecks = 25000))
    }

    @Test
    fun `clamp never exceeds max and never negative`() {
        assertEquals(25000L, BonusSpend.clamp(99999, 50000, 25000))
        assertEquals(0L, BonusSpend.clamp(-1, 5000, 25000))
    }
}
