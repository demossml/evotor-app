package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Инвариант кассы: на чек действует одна денежная скидка — либо ваучер, либо кэшбэк.
 * Правило живёт в модели состояния ([Choice.normalize]), а не только в UI.
 */
class CheckoutChoiceTest {

    @Test
    fun `voucher drops cashback`() {
        val c = Choice.normalize(free = false, cashback = true, cashbackKopecks = 5000L, voucherId = 12)
        assertEquals(12, c.voucherId)
        assertEquals(false, c.cashback)
        assertEquals(0L, c.cashbackKopecks)
    }

    @Test
    fun `cashback drops voucher`() {
        val c = Choice.normalize(free = false, cashback = true, cashbackKopecks = 5000L, voucherId = null)
        assertTrue(c.cashback)
        assertNull(c.voucherId)
    }

    @Test
    fun `nothing chosen means no discount and no voucher`() {
        val c = Choice.normalize(free = false, cashback = false, cashbackKopecks = 5000L, voucherId = null)
        assertEquals(false, c.cashback)
        assertEquals(0L, c.cashbackKopecks)
        assertNull(c.voucherId)
    }

    @Test
    fun `gift does not touch the money choice`() {
        val c = Choice.normalize(free = true, cashback = true, cashbackKopecks = 3000L, voucherId = null)
        assertTrue(c.free)
        assertTrue(c.cashback)
    }

    @Test
    fun `zero or negative voucher id is treated as absent`() {
        assertNull(Choice.normalize(free = false, cashback = false, cashbackKopecks = 0L, voucherId = 0).voucherId)
        assertNull(Choice.normalize(free = false, cashback = false, cashbackKopecks = 0L, voucherId = -3).voucherId)
    }
}
