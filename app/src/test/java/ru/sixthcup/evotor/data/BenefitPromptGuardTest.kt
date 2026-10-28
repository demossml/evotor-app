package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Окно льготы: ровно один показ на привязку карты. */
class BenefitPromptGuardTest {

    @Test
    fun `second call for the same card is silent`() {
        val g = BenefitPromptGuard()
        assertEquals(true, g.ask("payload.sig", hasOffer = true))
        assertFalse(g.ask("payload.sig", hasOffer = true))
        assertFalse(g.ask("payload.sig", hasOffer = true))
    }

    @Test
    fun `no offer means no prompt, and the card is still marked`() {
        val g = BenefitPromptGuard()
        assertFalse(g.ask("card-1", hasOffer = false))
        // Льгота появилась позже (например, обновилась карта) — второй раз всё равно не спрашиваем.
        assertFalse(g.ask("card-1", hasOffer = true))
    }

    @Test
    fun `another card gets its own prompt`() {
        val g = BenefitPromptGuard()
        assertEquals(true, g.ask("card-1", hasOffer = true))
        assertEquals(true, g.ask("card-2", hasOffer = true))
    }

    @Test
    fun `card cleared allows the prompt again`() {
        val g = BenefitPromptGuard()
        assertEquals(true, g.ask("card-1", hasOffer = true))
        g.onCardCleared()
        assertNull(g.askedCode())
        assertEquals(true, g.ask("card-1", hasOffer = true))
    }

    @Test
    fun `empty code never triggers the prompt`() {
        val g = BenefitPromptGuard()
        assertFalse(g.ask("", hasOffer = true))
        assertNull(g.askedCode())
    }
}
