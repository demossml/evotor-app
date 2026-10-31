package ru.sixthcup.evotor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор каталога: стабильная связка (`articleNumber`), категории-добавки по id
 * и `groups[]` как fallback для группировки объёмов.
 */
class CatalogPayloadParseTest {

    private val json = """
        {
          "ok": true,
          "storeUuid": "store-1",
          "addonCategoryIds": [4, 7],
          "products": [
            {
              "id": 42, "productId": 42, "name": "Карамель", "priceRub": 40,
              "articleNumber": "sc-42", "categoryId": 4, "categoryName": "Добавки",
              "evotorUuid": "u-42", "hideFromMenu": true, "isModifier": true,
              "toppings": [], "upsell": []
            },
            {
              "id": 77, "productId": 77, "name": "Капучино S", "priceRub": 200,
              "articleNumber": "sc-77", "categoryId": 1, "groupId": 5, "variantLabel": "S",
              "sizeValue": 200, "sizeUnit": "ml", "evotorUuid": "u-77",
              "toppings": [], "upsell": []
            }
          ],
          "groups": [
            { "id": 5, "name": "Капучино", "variants": [
              { "productId": 77, "evotorUuid": "u-77", "label": "S", "price": 200 }
            ] }
          ]
        }
    """.trimIndent()

    @Test
    fun `article, category and addon category ids are parsed`() {
        val payload = CatalogApi.parsePayload(json)
        assertEquals("sc-42", payload.byArticle["sc-42"]?.articleNumber)
        assertEquals("sc-77", payload.byArticle["sc-77"]?.articleNumber)
        assertEquals(setOf(4, 7), payload.addonCategoryIds)
        val addon = payload.byUuid["u-42"]
        assertNotNull(addon)
        assertEquals(4, addon!!.categoryId)
        assertTrue(addon.hideFromMenu)
    }

    @Test
    fun `features and sellable are parsed for server driven catalog`() {
        val body = """
            {
              "features": { "serverCatalog": true },
              "products": [
                { "productId": 1, "evotorUuid": "u-1", "name": "Капучино 250 мл", "sellable": true },
                { "productId": 2, "evotorUuid": "u-2", "name": "Раф", "sellable": false, "notReadyReason": "no_evotor_uuid" }
              ]
            }
        """.trimIndent()
        val payload = CatalogApi.parsePayload(body)
        assertEquals(true, payload.features?.serverCatalog)
        assertEquals(true, payload.byUuid["u-1"]?.sellable)
        assertEquals(false, payload.byUuid["u-2"]?.sellable)
        assertEquals("no_evotor_uuid", payload.byUuid["u-2"]?.notReadyReason)
    }

    @Test
    fun `old catalog without features keeps legacy path and sellable defaults to true`() {
        val payload = CatalogApi.parsePayload(json)
        assertEquals(null, payload.features)
        assertTrue(payload.byUuid.values.all { it.sellable })
        assertTrue(payload.byUuid.values.all { it.notReadyReason == null })
    }

    @Test
    fun `server groups provide productId and uuid fallbacks`() {
        val payload = CatalogApi.parsePayload(json)
        assertEquals(5, payload.groupByProductId[77])
        assertEquals(5, payload.groupByUuid["u-77"])
        assertEquals(5, payload.byUuid["u-77"]?.groupId)
    }
}
