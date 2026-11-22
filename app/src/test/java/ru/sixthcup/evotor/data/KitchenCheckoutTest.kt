package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** clientOrderId: стабилен внутри попытки, новый — для новой попытки. */
class KitchenCheckoutTest {

    @Test
    fun `first bind creates an id`() {
        val (id, changed) = KitchenCheckout.resolve(null, null, "p-1", "new")
        assertEquals("new", id)
        assertTrue(changed)
    }

    @Test
    fun `retry of the same attempt keeps the id`() {
        val (id, changed) = KitchenCheckout.resolve("p-1", "c-1", "p-1", "new")
        assertEquals("c-1", id)
        assertFalse(changed)
    }

    @Test
    fun `new attempt gets a new id`() {
        val (id, changed) = KitchenCheckout.resolve("p-1", "c-1", "p-2", "new")
        assertEquals("new", id)
        assertTrue(changed)
    }

    @Test
    fun `stored pending without client id is replaced`() {
        val (id, changed) = KitchenCheckout.resolve("p-1", null, "p-1", "new")
        assertEquals("new", id)
        assertTrue(changed)
    }
}
