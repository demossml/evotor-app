package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CartChoiceTest {

    private fun drink(uuid: String = "u-cap", price: String = "200") = CatalogProduct(
        uuid = uuid, name = "Капучино", priceRub = BigDecimal(price), allowToSell = true,
        recipe = null, description = null, article = null,
    )

    @Test fun `choice goes only to the drink line and does not change the sum`() {
        val lines = CartLines.of(drink(), emptyList(), null, "u-cap|ch:1", 2, choiceTitle = "Корица", choiceLabel = "С корицей")
        assertEquals(1, lines.size)
        assertEquals("С корицей", lines[0].choiceLabel)
        assertEquals("Корица", lines[0].choiceTitle)
        assertEquals(BigDecimal("400"), lines[0].lineTotal())
    }

    @Test fun `without choice nothing changes`() {
        val l = CartLines.of(drink(), emptyList(), null, "", 1).single()
        assertNull(l.choiceLabel)
        assertNull(l.choiceTitle)
        assertEquals("u-cap", l.cartKey)
    }

    @Test fun `paid milk keeps its own line and the choice stays on the drink`() {
        val milk = Topping(id = 5, name = "Кокосовое", priceRub = BigDecimal("80"), groupKey = "milk",
            evotorUuid = "u-coco", fromMilkScheme = true)
        val lines = CartLines.of(drink(), listOf(milk), null, "k", 1, choiceTitle = "Корица", choiceLabel = "Без корицы")
        assertEquals(2, lines.size)
        assertEquals("Без корицы", lines[0].choiceLabel)
        assertNull(lines[1].choiceLabel)
    }

    @Test fun `cart json round trip keeps and defaults the choice`() {
        val withChoice = CartLine("u", "Капучино", BigDecimal("200"), choiceLabel = "С корицей", choiceTitle = "Корица")
        val plain = CartLine("u2", "Эспрессо", BigDecimal("150"))
        val arr = Cart.encode(listOf(withChoice, plain))
        val a = Cart.decode(arr.getJSONObject(0))!!
        val b = Cart.decode(arr.getJSONObject(1))!!
        assertEquals("С корицей", a.choiceLabel)
        assertEquals("Корица", a.choiceTitle)
        assertNull(b.choiceLabel)
    }

    @Test fun `old cart json without choice keys decodes`() {
        val o = org.json.JSONObject().put("u", "u").put("n", "Капучино").put("p", "200").put("q", "1")
        val l = Cart.decode(o)!!
        assertNull(l.choiceLabel)
    }

    @Test fun `validator reports choice_missing only when a choice is required`() {
        val p = drink()
        val noChoice = CartLine("u-cap", "Капучино", BigDecimal("200"))
        val withChoice = noChoice.copy(choiceLabel = "С корицей", choiceTitle = "Корица")
        assertTrue(ReceiptValidator.validate(listOf(noChoice), listOf(p)).isEmpty())
        assertEquals("choice_missing", ReceiptValidator.validate(listOf(noChoice), listOf(p)) { true }.single().reason)
        assertTrue(ReceiptValidator.validate(listOf(withChoice), listOf(p)) { true }.isEmpty())
    }

    @Test fun `repeat never restores a line with choice`() {
        val p = drink()
        val line = CartLine("u-cap", "Капучино", BigDecimal("200"), choiceLabel = "С корицей", choiceTitle = "Корица")
        val plan = RepeatOrder.plan(listOf(line), listOf(p)) { true }
        assertTrue(plan.restore.isEmpty())
        assertEquals(1, plan.needsChoice.size)
        val plain = RepeatOrder.plan(listOf(line.copy(choiceLabel = null, choiceTitle = null)), listOf(p)) { true }
        assertEquals(1, plain.restore.size)
        assertFalse(plain.needsChoice.isNotEmpty())
    }
}
