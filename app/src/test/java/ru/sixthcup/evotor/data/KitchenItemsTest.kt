package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Корзина → позиции для бариста: одна строка напитка, модификаторы читаемые, служебное не дублируется. */
class KitchenItemsTest {

    private fun main(
        name: String = "Капучино 350 мл",
        qty: String = "1",
        recipe: String? = "350 мл · овсяное · ванильный",
        choice: String? = null,
        uuid: String = "u-cap",
        key: String = "u-cap",
    ) = CartLine(
        productUuid = uuid,
        name = name,
        priceRub = BigDecimal("300"),
        quantity = BigDecimal(qty),
        recipe = recipe,
        cartKey = key,
        choiceLabel = choice,
    )

    private fun option(name: String, uuid: String = "u-milk") = CartLine(
        productUuid = uuid,
        name = name,
        priceRub = BigDecimal("80"),
        quantity = BigDecimal.ONE,
        optionId = 5,
    )

    @Test
    fun `one drink with two mods is a single kitchen item`() {
        val items = KitchenItems.fromCart(listOf(main()))
        assertEquals(1, items.size)
        assertEquals("Капучино 350 мл", items[0].name)
        assertEquals(1, items[0].qty)
        assertEquals(listOf("Овсяное", "Ванильный"), items[0].mods)
    }

    @Test
    fun `size is not repeated when the product name already has it`() {
        val items = KitchenItems.fromCart(listOf(main(recipe = "350 мл · овсяное")))
        assertEquals(listOf("Овсяное"), items[0].mods)
    }

    @Test
    fun `paid option lines are not separate menu items`() {
        val items = KitchenItems.fromCart(listOf(main(), option("Овсяное молоко (Капучино 350 мл)")))
        assertEquals(1, items.size)
        assertEquals("Капучино 350 мл", items[0].name)
    }

    @Test
    fun `quantity is carried and never below one`() {
        val items = KitchenItems.fromCart(listOf(main(qty = "3"), main(qty = "0", key = "other")))
        assertEquals(3, items[0].qty)
        assertEquals(1, items[1].qty)
    }

    @Test
    fun `choice label becomes a mod`() {
        val items = KitchenItems.fromCart(listOf(main(recipe = null, choice = "С корицей")))
        assertEquals(listOf("С корицей"), items[0].mods)
    }

    @Test
    fun `duplicate mods collapse`() {
        val items = KitchenItems.fromCart(listOf(main(recipe = "Овсяное · овсяное", choice = "овсяное")))
        assertEquals(listOf("Овсяное"), items[0].mods)
    }

    @Test
    fun `cart with only option lines still yields items`() {
        val items = KitchenItems.fromCart(listOf(option("Сироп ванильный")))
        assertEquals(1, items.size)
        assertEquals("Сироп ванильный", items[0].name)
    }

    @Test
    fun `empty cart is empty order`() {
        assertTrue(KitchenItems.fromCart(emptyList()).isEmpty())
    }
}
