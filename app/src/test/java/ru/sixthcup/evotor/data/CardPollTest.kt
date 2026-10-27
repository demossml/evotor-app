package ru.sixthcup.evotor.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Гейт фонового опроса карты: только при привязанной карте, видимом экране и не чаще 30 с. */
class CardPollTest {

    @Test
    fun `first tick refreshes immediately`() {
        assertTrue(CardPoll.shouldRefresh(hasCard = true, visible = true, lastAtMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun `no card means no polling`() {
        assertFalse(CardPoll.shouldRefresh(hasCard = false, visible = true, lastAtMs = 0L, nowMs = 1_000_000L))
    }

    @Test
    fun `hidden screen means no polling`() {
        assertFalse(CardPoll.shouldRefresh(hasCard = true, visible = false, lastAtMs = 0L, nowMs = 1_000_000L))
    }

    @Test
    fun `interval is respected`() {
        val last = 1_000_000L
        assertFalse(CardPoll.shouldRefresh(true, true, last, last + 29_999L))
        assertTrue(CardPoll.shouldRefresh(true, true, last, last + CardPoll.INTERVAL_MS))
        assertTrue(CardPoll.shouldRefresh(true, true, last, last + 120_000L))
    }

    @Test
    fun `interval is not a couple of seconds`() {
        // Защита от «разрешить себе resolve каждые 2 секунды».
        assertTrue(CardPoll.INTERVAL_MS >= 30_000L)
    }
}
