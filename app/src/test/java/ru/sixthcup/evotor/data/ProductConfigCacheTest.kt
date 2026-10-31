package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.ProductConfigApi

/** Кэш конфигурации переживает запись/чтение без потери схем (нужен для офлайна). */
class ProductConfigCacheTest {

    private val store = "STORE-UUID"

    private fun config(): ProductConfig = ProductConfig(
        schemaVersion = 1,
        storeUuid = store,
        productId = 501,
        volumes = listOf(
            VolumeOption(501, "VOL-250", "Капучино 250 мл", "250 мл", 250, "ml", java.math.BigDecimal("250"), true),
            VolumeOption(502, "VOL-350", "Капучино 350 мл", "350 мл", 350, "ml", java.math.BigDecimal("300"), true),
        ),
        milk = MilkConfig(
            required = true,
            byProductId = mapOf(
                501 to MilkScheme(
                    schemeId = 7,
                    options = listOf(
                        MilkOption(1, null, "Обычное молоко", null, isFree = true, isDefault = true, sendToReceipt = false, available = true),
                        MilkOption(2, 900, "Кокосовое", "COCONUT", isFree = false, isDefault = false, sendToReceipt = true, available = true),
                    ),
                ),
            ),
        ),
        additions = AdditionsConfig(
            required = false,
            byProductId = mapOf(
                501 to AdditionScheme(
                    schemeId = 12,
                    options = listOf(
                        AdditionOption(21, 901, "Ванильный сироп", "syrup", "VANILLA", java.math.BigDecimal("50"), false, 1, false, false, true, true),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `cache roundtrip keeps volumes, milk and additions`() {
        val json = ProductConfigCache.toJson(config())
        val back = ProductConfigApi.parse(json.getJSONObject("config").toString(), 501, store).config
        assertNotNull(back)
        assertEquals(2, back!!.volumes.size)
        assertEquals(2, back.milkFor(501)!!.options.size)
        assertTrue(back.milk!!.required)
        assertEquals(1, back.additionsFor(501)!!.options.size)
        assertEquals(true, back.milkFor(501)!!.options.first().isFree)
    }

    @Test
    fun `cache json declares schema version and store`() {
        val cfg = ProductConfigCache.toJson(config()).getJSONObject("config")
        assertEquals(1, cfg.optInt("schemaVersion"))
        assertEquals(store, cfg.getString("storeUuid"))
    }
}
