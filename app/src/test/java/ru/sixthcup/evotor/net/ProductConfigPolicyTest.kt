package ru.sixthcup.evotor.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.data.ProductConfig

/**
 * АУДИТ Q1/Q2: когда касса имеет право добавить товар в чек **без окна** конфигурации.
 * Право есть только если сервер явно подтвердил, что схем нет. Сетевая ошибка, 404 и
 * отсутствие productId такого права не дают: у товара может быть обязательная схема молока.
 */
class ProductConfigPolicyTest {

    private fun noConfigResult() = ProductConfigApi.FetchResult(
        config = ProductConfig(schemaVersion = 1, storeUuid = "S", productId = 501),
        httpStatus = 200,
        noConfiguration = true,
    )

    @Test
    fun `silent add is allowed only when server confirmed no configuration`() {
        assertTrue(ProductConfigPolicy.silentAddAllowed(noConfigResult()))
    }

    @Test
    fun `network error does not allow silent add`() {
        assertFalse(ProductConfigPolicy.silentAddAllowed(ProductConfigApi.FetchResult(null, 0, "timeout")))
        assertFalse(ProductConfigPolicy.silentAddAllowed(null))
    }

    @Test
    fun `unsupported route does not allow silent add`() {
        assertFalse(
            ProductConfigPolicy.silentAddAllowed(ProductConfigApi.FetchResult(null, 404, "HTTP 404", unsupported = true)),
        )
    }

    @Test
    fun `forbidden or server error does not allow silent add`() {
        assertFalse(ProductConfigPolicy.silentAddAllowed(ProductConfigApi.FetchResult(null, 403, "forbidden")))
        assertFalse(ProductConfigPolicy.silentAddAllowed(ProductConfigApi.FetchResult(null, 500, "server")))
    }

    @Test
    fun `config with schemes requires the sheet`() {
        val cfg = ProductConfig(
            schemaVersion = 1, storeUuid = "S", productId = 501,
            milk = ru.sixthcup.evotor.data.MilkConfig(required = true, byProductId = emptyMap()),
        )
        assertFalse(
            ProductConfigPolicy.silentAddAllowed(ProductConfigApi.FetchResult(cfg, 200)),
        )
    }
}
