package ru.sixthcup.evotor.sell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.data.AdditionOption
import ru.sixthcup.evotor.data.AdditionScheme
import ru.sixthcup.evotor.data.AdditionsConfig
import ru.sixthcup.evotor.data.CartLine
import ru.sixthcup.evotor.data.CartLines
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.FiscalLines
import ru.sixthcup.evotor.data.FiscalSplit
import ru.sixthcup.evotor.data.MilkConfig
import ru.sixthcup.evotor.data.MilkOption
import ru.sixthcup.evotor.data.MilkScheme
import ru.sixthcup.evotor.data.ProductConfig
import ru.sixthcup.evotor.data.Topping
import ru.sixthcup.evotor.data.VolumeOption
import ru.sixthcup.evotor.ui.ConfigOptsMapper
import java.math.BigDecimal

/**
 * Боевой путь чека: выбор кассира → [CartLines] → [FiscalLines] → [ReceiptPlan] → позиции Эвотор.
 *
 * Проверяется тот же код, который вызывают `MainActivity` («В чек») и `SellLauncher`
 * (открытие чека продажи), а не отдельный вспомогательный метод.
 */
class ReceiptPlanTest {

    private fun d(s: String) = BigDecimal(s)

    private val variant = CatalogProduct(
        uuid = "VOL-250", name = "Капучино 250 мл", priceRub = d("250"), allowToSell = true,
        recipe = null, description = null, article = "1001",
    )

    private fun milk(id: Int, label: String, uuid: String?, free: Boolean = false) = Topping(
        id = id, name = label, priceRub = if (free) BigDecimal.ZERO else d("60"), groupKey = "milk",
        evotorUuid = uuid, zeroOption = free, sendToReceipt = !free, available = true,
    )

    private fun addon(id: Int, name: String, uuid: String?, price: String = "50", send: Boolean = true) = Topping(
        id = id, name = name, priceRub = d(price), groupKey = "syrup",
        evotorUuid = uuid, sendToReceipt = send, available = true,
    )

    /** Номенклатура терминала: цена «как в каталоге Эвотор». */
    private val terminalItems = mapOf(
        "VOL-250" to TerminalItem("VOL-250", d("250")),
        "MILK-COCONUT" to TerminalItem("MILK-COCONUT", d("60")),
        "SYRUP-VANILLA" to TerminalItem("SYRUP-VANILLA", d("50")),
        "EXTRA-CARAMEL" to TerminalItem("EXTRA-CARAMEL", d("50")),
    )

    private fun noDiscount() = FiscalSplit(
        positionDiscountKopecks = 0L, receiptDiscountKopecks = 0L,
        giftApplied = false, voucherId = null, floorApplied = false,
    )

    private fun decide(chosen: List<Topping>, qty: Int = 1): ReceiptPlan.Result {
        val cartLines = CartLines.of(variant, chosen, "250 мл", "VOL-250|sig", qty)
        return ReceiptPlan.plan(FiscalLines.build(cartLines, noDiscount())) { terminalItems[it] }
    }

    // 1. Основной товар — объём, одна позиция, без дубля.
    @Test
    fun `main product is a single position`() {
        val plan = decide(emptyList())
        assertEquals(1, plan.positions.size)
        assertEquals("VOL-250", plan.positions[0].productUuid)
        assertEquals(d("250"), plan.positions[0].price)
        assertEquals(BigDecimal.ONE, plan.positions[0].quantity)
        assertNull(ReceiptPlan.blockReason(plan))
    }

    // 2. Бесплатное обычное молоко не создаёт позицию.
    @Test
    fun `free ordinary milk adds no position`() {
        val plan = decide(listOf(milk(1, "Обычное молоко", "MILK-REGULAR", free = true)))
        assertEquals(1, plan.positions.size)
        assertEquals("VOL-250", plan.positions[0].productUuid)
    }

    // 3. Платное молоко — отдельная позиция по своему uuid и цене терминала.
    @Test
    fun `paid milk is a separate position with catalog uuid and price`() {
        val plan = decide(listOf(milk(2, "Кокосовое молоко", "MILK-COCONUT")))
        assertEquals(listOf("VOL-250", "MILK-COCONUT"), plan.positions.map { it.productUuid })
        assertEquals(d("60"), plan.positions[1].price)
        assertNull(ReceiptPlan.blockReason(plan))
    }

    // 4. Добавка — отдельная позиция по своему uuid.
    @Test
    fun `addition is a separate position`() {
        val plan = decide(listOf(addon(21, "Ванильный сироп", "SYRUP-VANILLA")))
        assertEquals(listOf("VOL-250", "SYRUP-VANILLA"), plan.positions.map { it.productUuid })
        assertEquals(d("50"), plan.positions[1].price)
    }

    // 5. Несколько добавок — каждая своей позицией, без дублей.
    @Test
    fun `several additions produce one position each`() {
        val plan = decide(
            listOf(
                addon(21, "Ванильный сироп", "SYRUP-VANILLA"),
                addon(22, "Карамель", "EXTRA-CARAMEL"),
            ),
        )
        assertEquals(listOf("VOL-250", "SYRUP-VANILLA", "EXTRA-CARAMEL"), plan.positions.map { it.productUuid })
        assertEquals(plan.positions.size, plan.positions.map { it.productUuid }.distinct().size)
    }

    // 6. sendToReceipt=false — опция в чек не попадает.
    @Test
    fun `option with sendToReceipt false stays out of the receipt`() {
        val plan = decide(listOf(addon(23, "Корица", "EXTRA-CARAMEL", send = false)))
        assertEquals(1, plan.positions.size)
        assertEquals("VOL-250", plan.positions[0].productUuid)
    }

    // 7. Количество применяется к каждой позиции (одинаковые опции склеиваются в строку).
    @Test
    fun `quantity is applied to every position`() {
        val plan = decide(
            listOf(milk(2, "Кокосовое молоко", "MILK-COCONUT"), addon(21, "Ванильный сироп", "SYRUP-VANILLA")),
            qty = 2,
        )
        assertEquals(3, plan.positions.size)
        assertTrue(plan.positions.all { it.quantity.compareTo(d("2")) == 0 })
    }

    // 8. Цена в чеке — из номенклатуры терминала, расхождение фиксируется.
    @Test
    fun `price comes from the terminal catalog`() {
        val cartLines = CartLines.of(variant, emptyList(), null, "VOL-250")
            .map { it.copy(priceRub = d("999")) }
        val plan = ReceiptPlan.plan(FiscalLines.build(cartLines, noDiscount())) { terminalItems[it] }
        assertEquals(d("250"), plan.positions[0].price)
        assertEquals(listOf("VOL-250"), plan.priceMismatch)
    }

    // 9. Цена со скидкой ставится только когда она ниже цены позиции.
    @Test
    fun `discount price is set only when lower`() {
        val cartLines = CartLines.of(variant, emptyList(), null, "VOL-250")
        val lower = FiscalLines.build(cartLines, noDiscount()).map { it.copy(unitPriceWithDiscount = d("1.00")) }
        assertEquals(d("1.00"), ReceiptPlan.plan(lower) { terminalItems[it] }.positions[0].discountPrice)

        val higher = FiscalLines.build(cartLines, noDiscount()).map { it.copy(unitPriceWithDiscount = d("300")) }
        assertNull(ReceiptPlan.plan(higher) { terminalItems[it] }.positions[0].discountPrice)
    }

    // 10. Синтетическая добавка без uuid Эвотор в чек не идёт.
    @Test
    fun `synthetic option is skipped`() {
        val cartLines = ArrayList(CartLines.of(variant, emptyList(), null, "VOL-250"))
        cartLines.add(CartLine("mod:milk", "Молоко", BigDecimal.ZERO))
        val plan = ReceiptPlan.plan(FiscalLines.build(cartLines, noDiscount())) { terminalItems[it] }
        assertEquals(1, plan.positions.size)
        assertEquals(1, plan.skippedSynthetic)
    }

    // 11. Полный путь: конфигурация → опции окна → корзина → позиции чека.
    @Test
    fun `full path from configuration to receipt positions`() {
        val opts = ConfigOptsMapper.all(config(), "VOL-250") { terminalItems[it]?.price }!!
        val chosen = listOf(
            opts.milk.first { it.label.startsWith("Кокосовое") }.toTopping("milk"),
            opts.syrup.first().toTopping("syrup"),
        )
        val lines = CartLines.of(variant, chosen, "250 мл", "VOL-250|m:c:m:2|s:c:a:21")
        val plan = ReceiptPlan.plan(FiscalLines.build(lines, noDiscount())) { terminalItems[it] }

        assertEquals(listOf("VOL-250", "MILK-COCONUT", "SYRUP-VANILLA"), plan.positions.map { it.productUuid })
        assertEquals(listOf(d("250"), d("60"), d("50")), plan.positions.map { it.price })
        assertNull(ReceiptPlan.blockReason(plan))
        assertFalse(plan.positions.any { it.discountPrice != null })
        assertTrue(plan.missing.isEmpty())
    }

    // 12. Бесплатное молоко в полном пути: позиция одна, молоко остаётся в рецепте.
    @Test
    fun `full path with free milk keeps a single position`() {
        val opts = ConfigOptsMapper.all(config(), "VOL-250") { terminalItems[it]?.price }!!
        val regular = opts.milk.first { it.label.startsWith("Обычное") }.toTopping("milk")
        val lines = CartLines.of(variant, listOf(regular), "250 мл", "VOL-250|m:c:m:1")
        val plan = ReceiptPlan.plan(FiscalLines.build(lines, noDiscount())) { terminalItems[it] }
        assertEquals(1, plan.positions.size)
        assertEquals("VOL-250", plan.positions[0].productUuid)
        assertEquals("250 мл", lines[0].recipe)
    }

    // 13. Основного товара нет в номенклатуре: позиция не создаётся, чек блокируется с uuid.
    @Test
    fun `missing main product blocks the receipt with its uuid`() {
        val plan = decideWithTerminal(emptyList(), terminalItems - "VOL-250")
        assertTrue("позиция не создаётся", plan.positions.isEmpty())
        assertEquals(listOf("VOL-250"), plan.missing.map { it.productUuid })
        val reason = ReceiptPlan.blockReason(plan)
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("VOL-250"))
    }

    // 14. Платного молока нет в номенклатуре: чек блокируется, строка не теряется молча.
    @Test
    fun `missing paid milk blocks the receipt`() {
        val plan = decideWithTerminal(
            listOf(milk(2, "Кокосовое молоко", "MILK-COCONUT")),
            terminalItems - "MILK-COCONUT",
        )
        assertEquals(listOf("MILK-COCONUT"), plan.missing.map { it.productUuid })
        assertEquals("основной товар не продаётся частично", listOf("VOL-250"), plan.positions.map { it.productUuid })
        val reason = ReceiptPlan.blockReason(plan)
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("MILK-COCONUT"))
    }

    // 15. Добавки нет в номенклатуре: чек блокируется.
    @Test
    fun `missing addition blocks the receipt`() {
        val plan = decideWithTerminal(
            listOf(addon(21, "Ванильный сироп", "SYRUP-VANILLA")),
            terminalItems - "SYRUP-VANILLA",
        )
        assertEquals(listOf("SYRUP-VANILLA"), plan.missing.map { it.productUuid })
        val reason = ReceiptPlan.blockReason(plan)
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("SYRUP-VANILLA"))
    }

    // 16. Обычный чек блокировки не имеет.
    @Test
    fun `complete receipt is not blocked`() {
        assertNull(ReceiptPlan.blockReason(decide(emptyList())))
    }

    // 17. Предварительная проверка номенклатуры (до резервирования номера очереди):
    // синтетические и пустые uuid не проверяются, повторы схлопываются.
    @Test
    fun `cartUuids lists unique real products only`() {
        val lines = listOf(
            CartLine("VOL-250", "Капучино", d("250")),
            CartLine("MILK-COCONUT", "Кокосовое молоко", d("60"), optionId = 2),
            CartLine("VOL-250", "Капучино", d("250")),
            CartLine("mod:milk", "Молоко", BigDecimal.ZERO),
            CartLine("", "Пустая", BigDecimal.ZERO),
        )
        assertEquals(listOf("VOL-250", "MILK-COCONUT"), ReceiptPlan.cartUuids(lines))
    }

    // 18. Сообщение об отсутствующем товаре содержит uuid — по нему ищут товар в Эвоторе.
    @Test
    fun `missing message names the product uuid`() {
        val text = ReceiptPlan.missingMessage(listOf(MissingProduct("MILK-COCONUT", "Кокосовое молоко")))
        assertTrue(text, text.contains("MILK-COCONUT"))
        assertTrue(text, text.contains("Кокосовое молоко"))
    }

    private fun decideWithTerminal(chosen: List<Topping>, terminal: Map<String, TerminalItem>): ReceiptPlan.Result {
        val cartLines = CartLines.of(variant, chosen, "250 мл", "VOL-250|sig")
        return ReceiptPlan.plan(FiscalLines.build(cartLines, noDiscount())) { terminal[it] }
    }

    private fun config() = ProductConfig(
        schemaVersion = 1, storeUuid = "S", productId = 501,
        volumes = listOf(VolumeOption(501, "VOL-250", variant.name, "250 мл", 250, "ml", d("250"), true)),
        milk = MilkConfig(
            required = true,
            byProductId = mapOf(
                501 to MilkScheme(
                    7,
                    listOf(
                        MilkOption(
                            1, null, "Обычное молоко", null, isFree = true, isDefault = true,
                            sendToReceipt = false, available = true,
                        ),
                        MilkOption(
                            2, 900, "Кокосовое молоко", "MILK-COCONUT", isFree = false, isDefault = false,
                            sendToReceipt = true, available = true,
                        ),
                    ),
                ),
            ),
        ),
        additions = AdditionsConfig(
            required = false,
            byProductId = mapOf(
                501 to AdditionScheme(
                    12,
                    listOf(
                        AdditionOption(
                            21, 901, "Ванильный сироп", "syrup", "SYRUP-VANILLA", d("50"),
                            required = false, maxCount = 1, isDefault = false, zeroOption = false,
                            available = true, sendToReceipt = true,
                        ),
                    ),
                ),
            ),
        ),
    )
}
