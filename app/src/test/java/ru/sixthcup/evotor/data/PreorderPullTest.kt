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
        assertTrue(PreorderPull.notice(plan).contains("Ни одна позиция не добавлена"))
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
        assertTrue(PreorderPull.notice(plan).startsWith("Добавлено 1 поз."))
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

    // Промпт 2.4: платная опция с uuid — отдельная позиция, цена из номенклатуры.
    @Test
    fun `paid modifier with uuid becomes its own position`() {
        val mods = listOf(
            OrderApi.Modifier("milk", "2", "Кокосовое молоко", 0, sendToReceipt = true, evotorUuid = "U2"),
        )
        val plan = PreorderPull.plan("o1", listOf(item("l1", "U1", qty = 2, mods = mods)), catalog)
        assertEquals(2, plan.added)
        assertEquals(1, plan.itemLines)
        val mod = plan.lines.single { it.isModifier }
        assertEquals("U2", mod.productUuid)
        assertEquals(BigDecimal("150"), mod.priceRub)
        assertEquals("pre:o1:l1:m:2", mod.cartKey)
        assertEquals("Капучино 250 мл", mod.recipe)
        // Итог диалога: 2×250 + 2×150.
        assertEquals(0, BigDecimal("800").compareTo(plan.totalRub))
    }

    @Test
    fun `free modifier without uuid is only a warning`() {
        val mods = listOf(
            OrderApi.Modifier("milk", "1", "Обычное молоко", 0, sendToReceipt = false, evotorUuid = null),
        )
        val plan = PreorderPull.plan("o1", listOf(item("l1", "U1", mods = mods)), catalog)
        assertEquals("позиция добавляется, опция — нет", 1, plan.added)
        assertTrue(plan.modifierNoUuid.isEmpty())
    }

    @Test
    fun `paid modifier without uuid warns but keeps the drink`() {
        val mods = listOf(
            OrderApi.Modifier("syrup", "3", "Ванильный сироп", 0, sendToReceipt = true, evotorUuid = null),
        )
        val plan = PreorderPull.plan("o1", listOf(item("l1", "U1", mods = mods)), catalog)
        assertEquals(1, plan.added)
        assertEquals(1, plan.modifierNoUuid.size)
        assertTrue(PreorderPull.notice(plan).contains("добавьте вручную"))
        // Без uuid опция остаётся текстом в рецепте.
        assertTrue(plan.lines.single().recipe!!.contains("Ванильный сироп"))
    }

    @Test
    fun `modifier missing in the terminal is reported`() {
        val mods = listOf(
            OrderApi.Modifier("syrup", "4", "Сироп лаванда", 0, sendToReceipt = true, evotorUuid = "GONE"),
        )
        val plan = PreorderPull.plan("o1", listOf(item("l1", "U1", mods = mods)), catalog)
        assertEquals(1, plan.added)
        assertEquals(listOf("Сироп лаванда"), plan.notFound)
    }

    @Test
    fun `quantity is never below one`() {
        val plan = PreorderPull.plan("o1", listOf(item("l1", "U1", qty = 0)), catalog)
        assertEquals(0, BigDecimal.ONE.compareTo(plan.lines.single().quantity))
    }

    // Контракт v2: `choice` (корица без цены) не становится позицией чека никогда.
    @Test
    fun `choice modifier never goes into the receipt`() {
        val mods = listOf(
            OrderApi.Modifier(
                group = "choice", id = "9", name = "Корица", priceEstimate = 0,
                sendToReceipt = true, evotorUuid = "U2", groupKey = "choice",
            ),
        )
        val plan = PreorderPull.plan("o1", listOf(item("l1", "U1", mods = mods)), catalog)
        assertEquals("только напиток", 1, plan.added)
        assertTrue(plan.lines.none { it.isModifier })
        assertTrue("выбор печатается отдельно, не предупреждаем", plan.modifierNoUuid.isEmpty())
        assertFalse(PreorderPull.recipeOf(item("l1", "U1", config = "250 мл · корица", mods = mods))!!.contains("Корица"))
    }

    @Test
    fun `receipt modifiers are grouped for the dialog`() {
        val mods = listOf(
            OrderApi.Modifier("milk", "2", "Кокосовое", 0, true, "U2", "milk"),
            OrderApi.Modifier("syrup", "3", "Ваниль", 0, true, "U1", "syrup"),
            OrderApi.Modifier("extra", "4", "Корица", 0, true, "U1", "choice"),
        )
        val plan = PreorderPull.plan("o1", listOf(item("l1", "U1", mods = mods)), catalog)
        assertEquals(3, plan.added)
        assertEquals(
            "молоко: Кокосовое; сироп: Ваниль",
            PreorderPull.groupedModifiers(plan.receiptModifiers),
        )
        assertTrue(PreorderPull.notice(plan).contains("Опции: молоко: Кокосовое; сироп: Ваниль"))
    }

    @Test
    fun `config text is printed as the backend sent it`() {
        val config = "300 мл · Кокосовое · С корицей · Ваниль"
        val recipe = PreorderPull.recipeOf(
            item("l1", "U1", config = config, mods = emptyList()).copy(volumeLabel = "300 мл"),
        )
        assertEquals(config, recipe)
    }
}
