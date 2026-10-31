package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Причина «в окне только объём» должна быть видна на экране: на терминале нет logcat.
 * Тексты проверяются дословно по смыслу — кассир/владелец читает именно их.
 */
class ConfigDiagnosticsTest {

    private fun fetch(
        productId: Int? = 501,
        storeUuid: String? = "STORE",
        requested: Boolean = true,
        httpStatus: Int = 200,
        unsupported: Boolean = false,
        error: String? = null,
        fromCache: Boolean = false,
    ) = ConfigDiagnostics.Fetch(productId, storeUuid, requested, httpStatus, unsupported, error, fromCache)

    private fun view(
        milk: Int = 0,
        additions: Int = 0,
        catalog: Int = 0,
        volumeMatched: Boolean = true,
        hasSchemes: Boolean = true,
    ) = ConfigDiagnostics.View(milk, additions, catalog, volumeMatched, hasSchemes)

    @Test
    fun `no hint when schemes are present`() {
        assertNull(ConfigDiagnostics.hint(fetch(), view(milk = 2)))
        assertNull(ConfigDiagnostics.hint(fetch(), view(additions = 3)))
    }

    @Test
    fun `no hint when catalog toppings are shown`() {
        assertNull(ConfigDiagnostics.hint(fetch(unsupported = true), view(catalog = 2)))
    }

    @Test
    fun `missing product id is reported`() {
        val text = ConfigDiagnostics.hint(fetch(productId = null, requested = false), view())
        assertTrue(text!!, text.contains("productId"))
    }

    @Test
    fun `missing store uuid is reported`() {
        val text = ConfigDiagnostics.hint(fetch(storeUuid = null, requested = false), view())
        assertTrue(text!!, text.contains("UUID точки"))
    }

    @Test
    fun `404 is reported as unsupported route`() {
        val text = ConfigDiagnostics.hint(fetch(httpStatus = 404, unsupported = true), view())
        assertTrue(text!!, text.contains("404"))
    }

    @Test
    fun `network error is reported`() {
        val text = ConfigDiagnostics.hint(fetch(httpStatus = 0, error = "timeout"), view())
        assertTrue(text!!, text.contains("сервер недоступен"))
    }

    @Test
    fun `cache without schemes is reported`() {
        val text = ConfigDiagnostics.hint(fetch(fromCache = true), view())
        assertTrue(text!!, text.contains("сохранённые данные"))
    }

    @Test
    fun `scheme exists but volume is not linked`() {
        val text = ConfigDiagnostics.hint(fetch(), view(volumeMatched = false))
        assertTrue(text!!, text.contains("Для этого объёма"))
    }

    @Test
    fun `scheme exists but options are unavailable`() {
        val text = ConfigDiagnostics.hint(fetch(), view(volumeMatched = true))
        assertTrue(text!!, text.contains("недоступны"))
    }

    @Test
    fun `no scheme at all on the server`() {
        val text = ConfigDiagnostics.hint(fetch(), view(hasSchemes = false))
        assertEquals("Молоко и добавки не назначены: у товара нет схемы на сервере.", text)
    }
}

/** Конфигурация относится к карточке и тогда, когда backend не связал объёмы с evotorUuid. */
class ProductConfigAppliesToTest {

    private fun cfg(volumes: List<VolumeOption>, milk: MilkConfig? = null) = ProductConfig(
        schemaVersion = 1, storeUuid = "S", productId = 501, volumes = volumes, milk = milk,
    )

    private val vol = VolumeOption(501, "VOL-250", "Капучино 250", "250 мл", 250, "ml", BigDecimal("250"), true)
    private val milk = MilkConfig(
        required = true,
        byProductId = mapOf(501 to MilkScheme(7, listOf(MilkOption(1, null, "Обычное", null, true, true, false, true)))),
    )

    @Test
    fun `volume match means the config applies`() {
        assertTrue(ProductConfigGuard.appliesTo(cfg(listOf(vol), milk), setOf("VOL-250"), 501))
    }

    @Test
    fun `unlinked volumes still apply when the config has schemes`() {
        val unlinked = vol.copy(evotorUuid = null)
        assertTrue(
            "иначе схемы молока молча обходились бы каталогом",
            ProductConfigGuard.appliesTo(cfg(listOf(unlinked), milk), setOf("VOL-250"), 501),
        )
    }

    @Test
    fun `config of another product does not apply`() {
        val unlinked = vol.copy(evotorUuid = null)
        assertTrue(!ProductConfigGuard.appliesTo(cfg(listOf(unlinked), milk), setOf("VOL-250"), 502))
    }

    @Test
    fun `config without schemes never blocks the catalog path`() {
        val unlinked = vol.copy(evotorUuid = null)
        assertTrue(!ProductConfigGuard.appliesTo(cfg(listOf(unlinked), null), setOf("VOL-250"), 501))
    }
}
