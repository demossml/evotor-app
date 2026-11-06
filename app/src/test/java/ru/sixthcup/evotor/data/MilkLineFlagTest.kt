package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.ui.OptionPicker
import java.math.BigDecimal

/** Флаг «молоко из схемы молока» должен дойти от опции окна до строки корзины. */
class MilkLineFlagTest {

    private val variant = CatalogProduct(
        uuid = "cap-350", name = "Капучино 350 мл", priceRub = BigDecimal("250"), allowToSell = true,
        recipe = null, description = null, article = null,
    )

    @Test
    fun `option key c-m maps to milk-scheme topping`() {
        val milk = OptionPicker.Opt(key = "c:m:7", label = "Кокосовое молоко", price = BigDecimal("80"), evotorUuid = "coconut", id = 7)
        assertTrue(milk.toTopping("milk").fromMilkScheme)
    }

    @Test
    fun `addon and catalog option keys do not set the flag`() {
        val addon = OptionPicker.Opt(key = "c:a:7", label = "Карамель", price = BigDecimal("30"), evotorUuid = "car", id = 7)
        val legacy = OptionPicker.Opt(key = "t:7", label = "Молоко старое", price = BigDecimal("30"), evotorUuid = "old", id = 7)
        assertFalse(addon.toTopping("extra").fromMilkScheme)
        assertFalse(legacy.toTopping("milk").fromMilkScheme)
    }

    @Test
    fun `cart lines keep the flag only on the paid milk line`() {
        val milk = OptionPicker.Opt(key = "c:m:7", label = "Кокосовое молоко", price = BigDecimal("80"), evotorUuid = "coconut", id = 7)
            .toTopping("milk")
        val addon = OptionPicker.Opt(key = "c:a:12", label = "Карамель", price = BigDecimal("30"), evotorUuid = "car", id = 12)
            .toTopping("extra")
        val lines = CartLines.of(variant, listOf(milk, addon), null, "sig")
        assertEquals(3, lines.size)
        assertFalse(lines[0].fromMilkScheme)          // объём
        assertTrue(lines[1].fromMilkScheme)           // молоко
        assertEquals(7, lines[1].optionId)            // optionId остаётся прежним
        assertFalse(lines[2].fromMilkScheme)          // добавка
    }

    @Test
    fun `free regular milk creates no line at all`() {
        val regular = OptionPicker.Opt(
            key = "c:m:1", label = "Обычное молоко", price = BigDecimal.ZERO, evotorUuid = null,
            zeroOption = true, sendToReceipt = false, id = 1,
        ).toTopping("milk")
        val lines = CartLines.of(variant, listOf(regular), null, "sig")
        assertEquals(1, lines.size)
    }
}
