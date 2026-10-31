package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Сценарии A–H из ТЗ «динамическая конфигурация напитков» §12 — на чистой функции сборки чека.
 * Здесь же фиксируется главное правило: обычное молоко показывается, но в чек не идёт.
 */
class DrinkConfigTest {

    private fun d(s: String) = BigDecimal(s)

    private fun variant(uuid: String = "cap-250", name: String = "Капучино 250 мл", price: String = "190") =
        CatalogProduct(
            uuid = uuid, name = name, priceRub = d(price), allowToSell = true,
            recipe = null, description = null, article = null,
        )

    private fun topping(
        id: Int,
        name: String,
        price: String,
        group: String,
        uuid: String? = null,
        zero: Boolean = false,
        send: Boolean = true,
        available: Boolean = true,
    ) = Topping(
        id = id, name = name, priceRub = d(price), groupKey = group,
        evotorUuid = uuid, zeroOption = zero, sendToReceipt = send, available = available,
    )

    private val regularMilk = topping(1, "Обычное молоко", "0", "milk", uuid = "milk-regular", zero = true)
    private val coconutMilk = topping(2, "Кокосовое молоко", "60", "milk", uuid = "milk-coconut")
    private val vanilla = topping(3, "Ванильный сироп", "50", "syrup", uuid = "syrup-vanilla")
    private val caramel = topping(4, "Карамель", "50", "extra", uuid = "extra-caramel")

    // A. Капучино: объём + молоко + добавки
    @Test
    fun `A - volume is the base line, ordinary milk never reaches the receipt`() {
        val lines = DrinkConfig.receiptLines(variant(), listOf(regularMilk, vanilla))
        assertEquals(2, lines.size)
        assertEquals("cap-250", lines[0].uuid)
        assertEquals(DrinkConfig.Role.VOLUME, lines[0].role)
        assertEquals("syrup-vanilla", lines[1].uuid)
        assertEquals(DrinkConfig.Role.ADDON, lines[1].role)
        assertFalse("обычное молоко не должно быть строкой чека", lines.any { it.uuid == "milk-regular" })
    }

    @Test
    fun `A - ordinary milk adds no money`() {
        val withRegular = DrinkConfig.receiptLines(variant(), listOf(regularMilk))
        assertEquals(d("190"), DrinkConfig.total(withRegular))
    }

    // B. Платное альтернативное молоко
    @Test
    fun `B - paid milk is a separate receipt line with its own uuid and price`() {
        val lines = DrinkConfig.receiptLines(variant(), listOf(coconutMilk, vanilla))
        val milk = lines.single { it.role == DrinkConfig.Role.MILK }
        assertEquals("milk-coconut", milk.uuid)
        assertEquals(d("60"), milk.priceRub)
        assertEquals(2, milk.optionId)
        assertEquals(d("190"), lines[0].priceRub)
        assertEquals(d("300"), DrinkConfig.total(lines))
    }

    // C. Товар без молока
    @Test
    fun `C - no milk group means no milk line`() {
        val tea = variant("tea-350", "Чай 350 мл", "150")
        val lines = DrinkConfig.receiptLines(tea, listOf(vanilla))
        assertEquals(0, lines.count { it.role == DrinkConfig.Role.MILK })
        assertEquals(2, lines.size)
    }

    // D. Товар без добавок
    @Test
    fun `D - no addons group means only the volume line`() {
        val lines = DrinkConfig.receiptLines(variant(), emptyList())
        assertEquals(1, lines.size)
        assertEquals(DrinkConfig.Role.VOLUME, lines.single().role)
    }

    // E/F. Нет объёмов / нет конфигурации — обычное добавление одной позиции
    @Test
    fun `E - product without configuration is a single line`() {
        val croissant = variant("croissant", "Круассан", "120")
        val lines = DrinkConfig.receiptLines(croissant, emptyList())
        assertEquals(listOf("croissant"), lines.map { it.uuid })
        assertEquals(d("120"), DrinkConfig.total(lines))
    }

    // G. Ошибки и защита
    @Test
    fun `G - option without uuid is never a receipt line`() {
        val noUuid = topping(5, "Сироп без товара", "50", "syrup", uuid = null)
        val lines = DrinkConfig.receiptLines(variant(), listOf(noUuid))
        assertEquals(1, lines.size)
        assertFalse(lines.any { it.uuid.isBlank() })
    }

    @Test
    fun `G - unknown or unavailable option is skipped`() {
        val disabled = topping(6, "Снят с продажи", "50", "extra", uuid = "extra-old", available = false)
        val lines = DrinkConfig.receiptLines(variant(), listOf(disabled))
        assertEquals(1, lines.size)
    }

    @Test
    fun `G - server may forbid sending an option to the receipt`() {
        val accountingOnly = topping(7, "Учётное молоко", "0", "milk", uuid = "milk-acc", send = false)
        val lines = DrinkConfig.receiptLines(variant(), listOf(accountingOnly))
        assertEquals(1, lines.size)
        assertFalse(DrinkConfig.goesToReceipt(accountingOnly, "cap-250"))
    }

    @Test
    fun `G - same product is not charged twice`() {
        val lines = DrinkConfig.receiptLines(variant(), listOf(caramel, caramel))
        assertEquals(2, lines.size)
        assertEquals(1, lines.count { it.uuid == "extra-caramel" })
    }

    @Test
    fun `G - option with the volume uuid is not duplicated`() {
        val sameAsVolume = topping(8, "Объём", "0", "extra", uuid = "cap-250")
        val lines = DrinkConfig.receiptLines(variant(), listOf(sameAsVolume))
        assertEquals(1, lines.size)
    }

    // H. Итог чека = сумма реальных позиций
    @Test
    fun `H - receipt total equals the sum of catalogue prices`() {
        val lines = DrinkConfig.receiptLines(variant(price = "230"), listOf(coconutMilk, vanilla, caramel))
        assertEquals(d("390"), DrinkConfig.total(lines))
        assertTrue(lines.all { it.priceRub.signum() >= 0 })
    }

    @Test
    fun `milk inside the addons group is still milk by groupKey`() {
        val oddMilk = topping(9, "Соевое", "70", "Молоко", uuid = "milk-soy")
        val lines = DrinkConfig.receiptLines(variant(), listOf(oddMilk))
        assertEquals(DrinkConfig.Role.MILK, lines[1].role)
    }

    // Конфигурация с сервера: молоко и добавки — раздельные блоки
    @Test
    fun `config milk - ordinary milk is not a receipt line even with uuid`() {
        val ordinary = MilkOption(
            id = 1, productId = null, label = "Обычное молоко", evotorUuid = "REGULAR-UUID",
            isFree = true, isDefault = true, sendToReceipt = false, available = true,
        )
        val lines = DrinkConfig.receiptLines(variant(), ordinary, emptyList())
        assertEquals(1, lines.size)
        assertEquals(d("190"), DrinkConfig.total(lines))
    }

    @Test
    fun `config milk - paid alternative becomes its own line`() {
        val coconut = MilkOption(
            id = 2, productId = 900, label = "Кокосовое молоко", evotorUuid = "COCONUT-UUID",
            isFree = false, isDefault = false, sendToReceipt = true, available = true,
        )
        val lines = DrinkConfig.receiptLines(variant(), coconut, emptyList())
        assertEquals(2, lines.size)
        assertEquals(DrinkConfig.Role.MILK, lines[1].role)
        assertEquals("COCONUT-UUID", lines[1].uuid)
    }

    @Test
    fun `config additions - paid additions are lines, zero options are not`() {
        val paid = AdditionOption(
            id = 21, productId = 901, name = "Ванильный сироп", groupKey = "syrup",
            evotorUuid = "VANILLA-UUID", priceRub = d("50"), required = false, maxCount = 2,
            isDefault = false, zeroOption = false, available = true, sendToReceipt = true,
        )
        val zero = AdditionOption(
            id = 22, productId = null, name = "Без сиропа", groupKey = "syrup",
            evotorUuid = null, priceRub = BigDecimal.ZERO, required = false, maxCount = 1,
            isDefault = false, zeroOption = true, available = true, sendToReceipt = false,
        )
        val lines = DrinkConfig.receiptLines(variant(), null, listOf(paid, zero))
        assertEquals(2, lines.size)
        assertEquals("VANILLA-UUID", lines[1].uuid)
        assertEquals(d("240"), DrinkConfig.total(lines))
    }
}
