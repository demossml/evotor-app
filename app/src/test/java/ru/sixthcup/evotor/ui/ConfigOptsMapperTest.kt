package ru.sixthcup.evotor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.data.AdditionScheme
import ru.sixthcup.evotor.data.AdditionsConfig
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.DrinkConfig
import ru.sixthcup.evotor.data.MilkConfig
import ru.sixthcup.evotor.data.MilkScheme
import ru.sixthcup.evotor.data.ProductConfig
import ru.sixthcup.evotor.data.ProductConfigGuard
import ru.sixthcup.evotor.data.ToppingRules
import ru.sixthcup.evotor.data.VolumeOption
import java.math.BigDecimal

/**
 * Этапы 2–4: обязательное молоко, обязательные добавки, схемы строго по объёму,
 * обычное молоко вне чека, платное молоко по uuid каталога.
 *
 * Проверяется реальный путь приложения: [ConfigOptsMapper] строит опции окна ровно теми
 * же правилами, что видит кассир, затем [ToppingRules.blockers] решает, можно ли отдать
 * позицию в чек, а [DrinkConfig.receiptLines] — что именно попадёт в чек.
 */
class ConfigOptsMapperTest {

    private fun d(s: String) = BigDecimal(s)

    /** Каталог Эвотор: uuid → цена. Всё, чего здесь нет, касса провести не может. */
    private val catalog = mapOf(
        "VOL-250" to d("250"),
        "VOL-350" to d("300"),
        "MILK-COCONUT" to d("60"),
        "SYRUP-VANILLA" to d("50"),
    )
    private val priceOf: (String) -> BigDecimal? = { catalog[it] }

    private fun volume(pid: Int, uuid: String, label: String) = VolumeOption(
        productId = pid, evotorUuid = uuid, name = "Капучино $label", label = label,
        sizeValue = null, sizeUnit = "ml", priceRub = catalog[uuid], available = true,
    )

    private fun milkOption(
        id: Int,
        label: String,
        uuid: String? = null,
        isFree: Boolean = false,
        send: Boolean = true,
        available: Boolean = true,
    ) = ru.sixthcup.evotor.data.MilkOption(
        id = id, productId = uuid?.let { 900 + id }, label = label, evotorUuid = uuid,
        isFree = isFree, isDefault = isFree, sendToReceipt = send, available = available,
    )

    private fun addition(
        id: Int,
        name: String,
        group: String,
        uuid: String? = null,
        price: String = "50",
        required: Boolean = false,
        send: Boolean = true,
        available: Boolean = true,
    ) = ru.sixthcup.evotor.data.AdditionOption(
        id = id, productId = uuid?.let { 950 + id }, name = name, groupKey = group, evotorUuid = uuid,
        priceRub = d(price), required = required, maxCount = 1, isDefault = false, zeroOption = false,
        available = available, sendToReceipt = send,
    )

    /** Капучино: два объёма, обязательное молоко, сироп; у 350 мл свой пустой набор молока. */
    private fun config(
        milkRequired: Boolean = true,
        schemes: Map<Int, MilkScheme>? = mapOf(
            501 to MilkScheme(
                7,
                listOf(milkOption(1, "Обычное молоко", isFree = true), milkOption(2, "Кокосовое", "MILK-COCONUT")),
            ),
            502 to MilkScheme(7, emptyList()),
        ),
    ) = ProductConfig(
        schemaVersion = 1, storeUuid = "S", productId = 501,
        volumes = listOf(volume(501, "VOL-250", "250 мл"), volume(502, "VOL-350", "350 мл")),
        milk = MilkConfig(required = milkRequired, byProductId = schemes ?: emptyMap()),
        additions = AdditionsConfig(
            required = false,
            byProductId = mapOf(
                501 to AdditionScheme(12, listOf(addition(21, "Ванильный сироп", "syrup", "SYRUP-VANILLA"))),
            ),
        ),
    )

    private fun blockersOf(opts: OptionPicker.Opts, selected: Set<String>): List<String> =
        ToppingRules.blockers(ConfigOptsMapper.ruleGroups(opts), selected)

    // 1. Обязательное молоко: без выбора позиция в чек не идёт.
    @Test
    fun `required milk blocks the receipt until an option is chosen`() {
        val opts = ConfigOptsMapper.all(config(), "VOL-250", priceOf)!!
        val blocked = blockersOf(opts, emptySet())
        assertEquals(1, blocked.size)
        assertTrue(blocked.first().contains("выберите вариант"))

        // Выбрано молоко без флага required — группа всё равно закрыта: выбор сделан.
        val chosen = setOf(opts.milk.first { it.label == "Кокосовое" }.key)
        assertTrue(blockersOf(opts, chosen).isEmpty())
    }

    // 2. Обычное молоко — zero-option: в чек не идёт, цену не добавляет.
    @Test
    fun `ordinary milk never reaches the receipt`() {
        val opts = ConfigOptsMapper.all(config(), "VOL-250", priceOf)!!
        val regular = opts.milk.first { it.label == "Обычное молоко" }
        assertTrue(regular.zeroOption)
        assertEquals(BigDecimal.ZERO, regular.price)
        assertTrue(blockersOf(opts, setOf(regular.key)).isEmpty())

        val lines = DrinkConfig.receiptLines(variant("VOL-250"), listOf(regular.toTopping("milk")))
        assertEquals(1, lines.size)
        assertEquals("VOL-250", lines[0].uuid)
    }

    // 3. Платное молоко — отдельная строка с uuid и ценой номенклатуры.
    @Test
    fun `paid milk goes to the receipt by catalog uuid and catalog price`() {
        val opts = ConfigOptsMapper.all(config(), "VOL-250", priceOf)!!
        val coconut = opts.milk.first { it.label == "Кокосовое" }
        assertEquals("MILK-COCONUT", coconut.evotorUuid)
        assertEquals(d("60"), coconut.price)

        val lines = DrinkConfig.receiptLines(variant("VOL-250"), listOf(coconut.toTopping("milk")))
        assertEquals(2, lines.size)
        assertEquals("MILK-COCONUT", lines[1].uuid)
        assertEquals(d("60"), lines[1].priceRub)
        assertEquals(DrinkConfig.Role.MILK, lines[1].role)
    }

    // 4. Платное молоко без товара Эвотор провести нельзя — опция недоступна.
    @Test
    fun `paid milk missing in the catalog is not selectable`() {
        val cfg = config(
            schemes = mapOf(501 to MilkScheme(7, listOf(milkOption(3, "Миндальное", "MILK-ALMOND")))),
        )
        val opts = ConfigOptsMapper.all(cfg, "VOL-250", priceOf)!!
        assertFalse(opts.milk.first().available)
        assertFalse(opts.milk.first().sellable)
        assertTrue(blockersOf(opts, emptySet()).isNotEmpty())
    }

    // 5. Схема молока привязана к объёму: у 350 мл своя (пустая) схема, чужая не подставляется.
    @Test
    fun `milk scheme is taken strictly for the selected volume`() {
        val opts250 = ConfigOptsMapper.all(config(), "VOL-250", priceOf)!!
        assertEquals(2, opts250.milk.size)

        assertNull("у 350 мл своей схемы нет — чужую не подставляем", ConfigOptsMapper.all(config(), "VOL-350", priceOf))
    }

    // 6. Если объём не опознан, окно не получает схем, а позицию не пускает в чек.
    @Test
    fun `unknown volume is blocked instead of falling back to another scheme`() {
        val cfg = config()
        assertNull(ConfigOptsMapper.all(cfg, "VOL-999", priceOf))
        val blocked = ProductConfigGuard.blockers(cfg, "VOL-999")
        assertEquals(1, blocked.size)
        assertTrue(blocked.first().contains("Объём"))
    }

    // 7. Обязательный объём: товара нет среди объёмов конфигурации — продавать нельзя.
    @Test
    fun `product that is not listed among config volumes is blocked`() {
        val cfg = config().copy(volumes = listOf(volume(501, "VOL-250", "250 мл")))
        val blocked = ProductConfigGuard.blockers(cfg, "VOL-350")
        assertEquals(1, blocked.size)
        assertTrue(blocked.first().contains("Объём"))
        assertTrue(ProductConfigGuard.blockers(cfg, "VOL-250").isEmpty())
    }

    // 8. Обязательное молоко, а схемы у объёма нет — продажа запрещена.
    @Test
    fun `missing milk scheme for a required group blocks the sale`() {
        val cfg = config(schemes = mapOf(501 to MilkScheme(7, emptyList())))
        val blocked = ProductConfigGuard.blockers(cfg, "VOL-250")
        assertEquals(1, blocked.size)
        assertTrue(blocked.first().contains("схемы молока"))
        // Схемы молока нет, но сироп у объёма есть — окно откроется, а молоко не покажет.
        val opts = ConfigOptsMapper.all(cfg, "VOL-250", priceOf)!!
        assertTrue(opts.milk.isEmpty())
        assertTrue(opts.syrup.isNotEmpty())
    }

    // 9. Необязательное молоко без схемы продажу не блокирует.
    @Test
    fun `optional milk without a scheme does not block the sale`() {
        val cfg = config(milkRequired = false, schemes = emptyMap())
        assertTrue(ProductConfigGuard.blockers(cfg, "VOL-250").isEmpty())
    }

    // 10. Обязательная добавка: без выбора группа не закрыта.
    @Test
    fun `required addition blocks the receipt until chosen`() {
        val cfg = config().copy(
            additions = AdditionsConfig(
                required = false,
                byProductId = mapOf(
                    501 to AdditionScheme(
                        12,
                        listOf(addition(21, "Ванильный сироп", "syrup", "SYRUP-VANILLA", required = true)),
                    ),
                ),
            ),
        )
        val opts = ConfigOptsMapper.all(cfg, "VOL-250", priceOf)!!
        // Молоко тоже обязательно, поэтому причин может быть две — добавка среди них.
        assertTrue(blockersOf(opts, emptySet()).any { it.contains("Сироп") })
        assertTrue(blockersOf(opts, setOf(opts.syrup.first().key)).none { it.contains("Сироп") })
    }

    // 11. sendToReceipt=false — опция видна, но отдельной позицией не идёт.
    @Test
    fun `addition with sendToReceipt false stays out of the receipt`() {
        val cfg = config().copy(
            additions = AdditionsConfig(
                required = false,
                byProductId = mapOf(
                    501 to AdditionScheme(12, listOf(addition(22, "Корица", "extra", "SYRUP-VANILLA", send = false))),
                ),
            ),
        )
        val opts = ConfigOptsMapper.all(cfg, "VOL-250", priceOf)!!
        val lines = DrinkConfig.receiptLines(variant("VOL-250"), listOf(opts.extra.first().toTopping("extra")))
        assertEquals(1, lines.size)
        assertEquals("VOL-250", lines[0].uuid)
    }

    // 12. Полный путь: объём + молоко + сироп, каждая позиция по своему uuid и цене каталога.
    @Test
    fun `full path gives volume, paid milk and syrup with catalog prices`() {
        val cfg = config()
        val opts = ConfigOptsMapper.all(cfg, "VOL-250", priceOf)!!
        val selected = setOf(
            opts.milk.first { it.label == "Кокосовое" }.key,
            opts.syrup.first().key,
        )
        assertTrue(blockersOf(opts, selected).isEmpty())
        assertTrue(ProductConfigGuard.blockers(cfg, "VOL-250").isEmpty())

        val chosen = listOf(
            opts.milk.first { it.label == "Кокосовое" }.toTopping("milk"),
            opts.syrup.first().toTopping("syrup"),
        )
        val lines = DrinkConfig.receiptLines(variant("VOL-250"), chosen)
        assertEquals(listOf("VOL-250", "MILK-COCONUT", "SYRUP-VANILLA"), lines.map { it.uuid })
        assertEquals(listOf(d("250"), d("60"), d("50")), lines.map { it.priceRub })
    }

    // 13. Несколько объёмов в конфигурации — окно обязательно, молчаливой продажи нет.
    @Test
    fun `config with two volumes requires the sheet`() {
        assertTrue(config().hasChoices)
    }

    // 14. Конфигурация без схем — обычное добавление разрешено.
    @Test
    fun `config without schemes allows plain add`() {
        val cfg = ProductConfig(schemaVersion = 1, storeUuid = "S", productId = 501)
        assertFalse(cfg.hasChoices)
        assertNull(ConfigOptsMapper.all(cfg, "VOL-250", priceOf))
        assertTrue(ProductConfigGuard.blockers(cfg, "VOL-250").isEmpty())
    }

    private fun variant(uuid: String) = CatalogProduct(
        uuid = uuid,
        name = if (uuid == "VOL-250") "Капучино 250 мл" else "Капучино 350 мл",
        priceRub = catalog[uuid] ?: BigDecimal.ZERO,
        allowToSell = true,
        recipe = null,
        description = null,
        article = null,
    )
}
