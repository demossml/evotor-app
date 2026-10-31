package ru.sixthcup.evotor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Этап 1 ТЗ: обязательные поля ответа конфигурации.
 *
 * Поведение: конфигурация с отсутствующим/пустым/неверного типа/чужим `productId` или
 * `storeUuid` **отклоняется** (config = null), а значит касса не имеет права добавлять
 * товар в чек молча — даже если схемы в ответе были.
 */
class ProductConfigStrictFieldsTest {

    private val store = "STORE-UUID"

    private fun body(
        productId: String? = "501",
        storeUuid: String? = "\"$store\"",
        volumes: String = """[{"productId":501,"evotorUuid":"VOL-250","name":"Капучино 250 мл","label":"250 мл","available":true}]""",
    ): String {
        val fields = listOfNotNull(
            "\"ok\":true",
            "\"schemaVersion\":1",
            storeUuid?.let { "\"storeUuid\":$it" },
            productId?.let { "\"productId\":$it" },
            "\"volumes\":$volumes",
        ).joinToString(",")
        return "{$fields}"
    }

    private fun parse(json: String) = ProductConfigApi.parse(json, 501, store)

    private fun assertRejected(json: String) {
        val res = parse(json)
        assertNull("конфигурация должна быть отклонена", res.config)
        assertNotNull("должна быть причина отказа", res.error)
        assertFalse(
            "без конфигурации молчаливое добавление запрещено",
            ProductConfigPolicy.silentAddAllowed(res),
        )
    }

    @Test
    fun `valid body is accepted`() {
        val res = parse(body())
        assertEquals(501, res.config?.productId)
        assertEquals(store, res.config?.storeUuid)
    }

    @Test
    fun `missing productId is rejected`() = assertRejected(body(productId = null))

    @Test
    fun `empty productId is rejected`() = assertRejected(body(productId = "\"\""))

    @Test
    fun `text productId is rejected`() = assertRejected(body(productId = "\"501\""))

    @Test
    fun `zero productId is rejected`() = assertRejected(body(productId = "0"))

    @Test
    fun `missing storeUuid is rejected`() = assertRejected(body(storeUuid = null))

    @Test
    fun `empty storeUuid is rejected`() = assertRejected(body(storeUuid = "\"\""))

    @Test
    fun `numeric storeUuid is rejected`() = assertRejected(body(storeUuid = "12345"))

    @Test
    fun `foreign storeUuid is rejected`() = assertRejected(body(storeUuid = "\"OTHER-STORE\""))

    @Test
    fun `foreign productId is rejected`() = assertRejected(body(productId = "502"))

    @Test
    fun `broken json is rejected without exception`() {
        assertRejected("{not json")
    }

    @Test
    fun `other schema version is rejected`() {
        assertRejected("""{"ok":true,"schemaVersion":2,"storeUuid":"$store","productId":501,"volumes":[]}""")
    }

    @Test
    fun `error body from server is rejected`() {
        assertRejected("""{"ok":false,"error":"no configuration"}""")
    }

    @Test
    fun `silent add allowed only for confirmed empty configuration`() {
        val empty = parse("""{"ok":true,"schemaVersion":1,"storeUuid":"$store","productId":501,"volumes":[]}""")
        assertNotNull(empty.config)
        assertTrue(ProductConfigPolicy.silentAddAllowed(empty))

        val withMilk = parse(
            body() .replace(
                "\"volumes\"",
                """"milk":{"required":true,"byProductId":{"501":{"schemeId":7,"options":[
                 {"id":1,"label":"Обычное молоко","evotorUuid":null,"isFree":true,"sendToReceipt":false,"available":true}]}}},"volumes"""",
            ),
        )
        assertNotNull(withMilk.config)
        assertFalse(ProductConfigPolicy.silentAddAllowed(withMilk))
    }

    @Test
    fun `fetch with unknown product id never returns a usable config`() {
        val res = ProductConfigApi.fetch(0, store)
        assertNull(res.config)
        assertFalse(ProductConfigPolicy.silentAddAllowed(res))
    }

    @Test
    fun `http failures never return a usable config`() {
        // Формы результатов, которые fetch отдаёт на 403 / 500 / сетевую ошибку.
        listOf(
            ProductConfigApi.FetchResult(null, 403, "Нет доступа"),
            ProductConfigApi.FetchResult(null, 500, "Ошибка сервера (HTTP 500)"),
            ProductConfigApi.FetchResult(null, 0, "timeout"),
        ).forEach { res ->
            assertNull(res.config)
            assertFalse(ProductConfigPolicy.silentAddAllowed(res))
        }
    }
}
