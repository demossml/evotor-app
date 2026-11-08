package ru.sixthcup.evotor.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Скан QR: авто-подтягивание только для готового заказа и пустой корзины. */
class PreorderAutoPullTest {

    @Test
    fun `ready order with an empty cart is pulled`() {
        assertTrue(PreorderAutoPull.shouldPull("ready", cartEmpty = true))
        assertTrue(PreorderAutoPull.shouldPull(" READY ", cartEmpty = true))
    }

    @Test
    fun `cart with items is never mixed with a preorder`() {
        assertFalse(PreorderAutoPull.shouldPull("ready", cartEmpty = false))
    }

    @Test
    fun `other statuses keep the order panel`() {
        listOf("draft", "accepted", "cooking", "paid", "rejected", "expired", null).forEach { s ->
            assertFalse("status=$s", PreorderAutoPull.shouldPull(s, cartEmpty = true))
        }
    }
}
