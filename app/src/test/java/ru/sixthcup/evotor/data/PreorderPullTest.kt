package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.OrderApi
import java.math.BigDecimal

/**
 * «Подтянуть в чек»: цена — из номенклатуры кассы, позиции без UUID Эвотор не попадают в чек,
 * модификаторы идут текстом (у них нет uuid), повторное нажатие не дублирует строки.
 */
class PreorderPullTest {

    private fun product(uuid: String, name: String, price: String, free: Boolean = false) = CatalogProduct(
        uuid = uuid, name = name, priceRub = BigDecimal(price), allowToSell = true,
        recipe = null, description = null, article = null, freeEligible = free,
    )

    private fun item(
        lineId: String,
        uuid: String?,
        name: String = "Капучино 250 мл",
        qty: Int = 1,
        config: String = "250 мл",
        mods: List<OrderApi.Modifier> = emptyList(),
    ) = OrderApi.Item(
        lineId = lineId,
        productId = "p-$lineId",
        evotorProductUuid = uuid,
        name = name,
        quantity = qty,
        volumeLabel = "250 мл",
        configText = config,
        unitPriceEstimate = 999,
        modifiers = mods,
    )

    private val catalog = mapOf(
        "U1" to product("U1", "Капучино 250 мл", "250"),
        "U2" to product("U2", "Круассан", "150", free = false),
    )

    @Test
    fun `lines take price from the terminal catalog, not the estimate`() {
        val plan = PreorderPull.plan("o1", listOf(item("l1", "U1", qty = 2)), catalog)
        assertEquals(1, plan.added)
        val line = plan.lines.single()
        assertEquals("U1", line.productUuid)
        assertEquals(BigDecimal("250"), line.priceRub)
        assertEquals(0, BigDecimal("2").compareTo(line.quantity))
        assertEquals("pre:o1:l1", line.cartKey)
    }

    @Test
    fun `item without evotor uuid is reported and skipped`() {
        val plan = PreorderPull.plan("o1", listOf(item("l1", null, name = "Напиток без UUID")), catalog)
        assertTrue(plan.lines.isEmpty())
        assertFalse(plan.hasAnything)
        assertEquals(listOf("Напиток без UUID"), plan.missingUuid)
        assertTrue(PreorderPull.notice(plan).contains("Не добавлено"))
        assertTrue(PreorderPull.notice(plan).contains("Напиток без UUID"))
    }

    @Test
    fun `product missing in the terminal is reported`() {
        val plan = PreorderPull.plan("o1", listOf(item("l1", "GONE", name = "Сезонный раф")), catalog)
        assertTrue(plan.lines.isEmpty())
        assertEquals(listOf("Сезонный раф"), plan.notFound)
    }

    @Test
    fun `mixed order adds what it can and warns about the rest`() {
        val plan = PreorderPull.plan(
            "o1",
            listOf(item("l1", "U1"), item("l2", null, name = "Без uuid"), item("l3", "GONE", name = "Нет в кассе")),
            catalog,
        )
        assertEquals(1, plan.added)
        assertEquals(1, plan.missingUuid.size)
        assertEquals(1, plan.notFound.size)
        assertTrue(PreorderPull.notice(plan).startsWith("В чеке: 1 поз."))
    }

    @Test
    fun `repeat pull does not duplicate lines`() {
        val items = listOf(item("l1", "U1"))
        val first = PreorderPull.plan("o1", items, catalog)
        val second = PreorderPull.plan("o1", items, catalog, alreadyInCart = first.lines.map { it.cartKey }.toSet())
        assertEquals(1, first.added)
        assertEquals(0, second.added)
        assertFalse(second.hasAnything)
    }

    @Test
    fun `recipe carries config text and only printable modifiers`() {
        val mods = listOf(
            OrderApi.Modifier("milk", "1", "Овсяное молоко", 70, sendToReceipt = true),
            OrderApi.Modifier("note", "2", "Служебная пометка", 0, sendToReceipt = false),
        )
        val recipe = PreorderPull.recipeOf(item("l1", "U1", config = "250 мл · обычное молоко", mods = mods))
        assertTrue(recipe!!, recipe.contains("250 мл"))
        assertTrue(recipe, recipe.contains("Овсяное молоко"))
        assertFalse("sendToReceipt=false не печатаем", recipe.contains("Служебная пометка"))
    }

    @Test
    fun `empty config still keeps the volume label`() {
        // Объём полезен баристе даже без остальной конфигурации.
        assertEquals("250 мл", PreorderPull.recipeOf(item("l1", "U1", config = "", mods = emptyList())))
    }

    @Test
    fun `no config, no volume, no modifiers give null recipe`() {
        val bare = item("l1", "U1", config = "").copy(volumeLabel = null)
        assertNull(PreorderPull.recipeOf(bare))
    }

    @Test
    fun `quantity is never below one`() {
        val plan = PreorderPull.plan("o1", listOf(item("l1", "U1", qty = 0)), catalog)
        assertEquals(0, BigDecimal.ONE.compareTo(plan.lines.single().quantity))
    }
}
