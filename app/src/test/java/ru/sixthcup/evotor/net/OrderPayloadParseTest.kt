package ru.sixthcup.evotor.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор `OrderOut` по контракту v2: `benefit`, `benefitTotal`, `quoteTotal`, `groupKey`,
 * `configText`. Поля могут отсутствовать или приходить как JSON-`null` — APK обязан работать.
 */
class OrderPayloadParseTest {

    private fun parse(json: String) = OrderApi.parseOrder(JSONObject(json))

    @Test
    fun `contract v2 fields are parsed`() {
        val o = parse(
            """
            {
              "id": "o-1", "shortCode": "123456", "storeId": 1, "status": "accepted",
              "createdAt": "2026-11-07T10:00:00.000Z", "expiresAt": "2026-11-07T10:30:00.000Z",
              "estimateTotal": 650, "currency": "RUB", "cardId": "0007",
              "benefit": { "kind": "gift", "title": "Подарок: Капучино 300 мл", "amountRub": 300 },
              "benefitTotal": 300, "quoteTotal": 350,
              "items": [ {
                "lineId": "l1", "productId": "910", "evotorProductUuid": "evo-910",
                "name": "Капучино 300 мл", "quantity": 1, "volumeLabel": "300 мл",
                "configText": "300 мл · кокосовое", "unitPriceEstimate": 650,
                "modifiers": [ {
                  "group": "milk", "id": "2", "name": "Кокосовое", "priceEstimate": 0,
                  "sendToReceipt": true, "evotorUuid": "evo-coconut", "groupKey": "milk"
                } ]
              } ],
              "warnings": []
            }
            """.trimIndent(),
        )
        assertEquals(650, o.estimateTotal)
        assertEquals(350, o.quoteTotal)
        assertEquals(300, o.benefitTotal)
        val b = o.benefit!!
        assertEquals("gift", b.kind)
        assertEquals("Подарок: Капучино 300 мл", b.title)
        assertEquals(300, b.amountRub)
        val item = o.items.single()
        assertEquals("300 мл · кокосовое", item.configText)
        assertEquals("milk", item.modifiers.single().groupKey)
    }

    @Test
    fun `missing v2 fields are null`() {
        val o = parse(
            """
            {
              "id": "o-2", "shortCode": "654321", "status": "draft",
              "estimateTotal": 250, "items": []
            }
            """.trimIndent(),
        )
        assertNull(o.benefit)
        assertNull(o.quoteTotal)
        assertEquals(0, o.benefitTotal)
        assertTrue(o.items.isEmpty())
    }

    @Test
    fun `explicit json null is not the string null`() {
        val o = parse(
            """
            {
              "id": "o-3", "shortCode": "111111", "status": "cooking",
              "estimateTotal": 100, "cardId": null, "benefit": null, "quoteTotal": null,
              "etaReadyAt": null, "serverNow": null, "etaMinutes": null,
              "items": [ {
                "lineId": "l1", "productId": "1", "evotorProductUuid": null,
                "name": "Чай", "quantity": 1, "volumeLabel": null, "configText": "",
                "unitPriceEstimate": 100,
                "modifiers": [ { "group": "milk", "id": "1", "name": "Обычное",
                  "priceEstimate": 0, "sendToReceipt": false, "evotorUuid": null, "groupKey": null } ]
              } ]
            }
            """.trimIndent(),
        )
        assertNull(o.benefit)
        assertNull(o.quoteTotal)
        assertNull(o.cardId)
        assertNull(o.etaReadyAt)
        assertNull(o.serverNow)
        assertNull(o.etaMinutes)
        assertNull(o.items.single().evotorProductUuid)
        assertNull(o.items.single().volumeLabel)
        assertNull(o.items.single().modifiers.single().evotorUuid)
        assertNull(o.items.single().modifiers.single().groupKey)
    }
}
