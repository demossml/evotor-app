package ru.sixthcup.evotor.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.ConfigDiagnostics
import ru.sixthcup.evotor.data.DrinkConfig
import ru.sixthcup.evotor.data.ProductConfigGuard
import java.math.BigDecimal

/**
 * Контракт backend ↔ касса: настоящий ответ `GET /api/devices/products/:id/configuration`
 * (файл генерирует backend-тест `products-configuration-fixture`). Изменение формата ломает этот тест.
 */
class ProductConfigContractTest {

    private val fx: JSONObject = JSONObject(
        javaClass.classLoader!!.getResourceAsStream("product-configuration-fixture.json")!!
            .bufferedReader().use { it.readText() },
    )
    private val store = fx.getString("store")
    private val capId = fx.getInt("cappuccinoId")
    private val espId = fx.getInt("espressoId")

    private fun cap(storeUuid: String = store, key: String = "cappuccino") =
        ProductConfigApi.parse(fx.getJSONObject(key).toString(), capId, storeUuid)

    private fun product(uuid: String, price: String = "200") = CatalogProduct(
        uuid = uuid, name = "Капучино", priceRub = BigDecimal(price), allowToSell = true,
        recipe = null, description = null, article = null,
    )

    @Test fun `cappuccino parses into three independent blocks`() {
        val c = cap().config!!
        assertEquals(listOf("u-cap-200", "u-cap-300"), c.volumes.map { it.evotorUuid })
        assertEquals(listOf(200, 300), c.volumes.map { it.sizeValue })
        assertTrue(c.volumes.all { it.available && it.notReadyReason == null })
        assertTrue(c.milk!!.required)
        assertEquals(setOf(capId, capId + 1), c.milk!!.byProductId.keys)
        assertNotNull(c.additions)
        assertTrue(c.configErrors.isEmpty())
    }

    @Test fun `plain milk has no uuid string null and never goes to receipt`() {
        val c = cap().config!!
        val opts = ProductConfigGuard.milkScheme(c, "u-cap-200")!!.options
        val plain = opts.first { it.isFree }
        assertNull(plain.evotorUuid)            // а не текст "null"
        assertFalse(plain.sendToReceipt)
        assertFalse(plain.receiptLine)
        assertTrue(plain.isDefault)
        val oat = opts.first { it.evotorUuid == "u-oat" }
        assertTrue(oat.receiptLine)
        assertNull(oat.notReadyReason)
        val coco = opts.first { it.label == "Кокосовое" }
        assertFalse(coco.available); assertFalse(coco.receiptLine)
        assertEquals("no_store_link", coco.notReadyReason)
        assertNull(coco.evotorUuid)
    }

    @Test fun `receipt is a flat list - volume plus paid milk plus paid addon, plain milk absent`() {
        val c = cap().config!!
        val milk = ProductConfigGuard.milkScheme(c, "u-cap-200")!!.options
        val adds = ProductConfigGuard.additionScheme(c, "u-cap-200")!!.options
        val variant = product("u-cap-200")
        val withPlain = DrinkConfig.receiptLines(variant, milk.first { it.isFree }, adds.filter { it.evotorUuid == "u-car" })
        assertEquals(listOf("u-cap-200", "u-car"), withPlain.map { it.uuid })
        val withOat = DrinkConfig.receiptLines(variant, milk.first { it.evotorUuid == "u-oat" }, adds)
        assertEquals(listOf("u-cap-200", "u-oat", "u-car"), withOat.map { it.uuid })
        // непродаваемое молоко (нет связи) в чек не попадает вообще
        assertEquals(listOf("u-cap-200"), DrinkConfig.receiptLines(variant, milk.first { it.label == "Кокосовое" }, emptyList()).map { it.uuid })
    }

    @Test fun `required milk blocks only when nothing is sellable`() {
        val c = cap().config!!
        assertTrue(ProductConfigGuard.blockers(c, "u-cap-200").isEmpty())
        assertTrue(ProductConfigGuard.blockers(c, "u-cap-300").isEmpty())
        assertEquals(listOf("Объём не опознан: выберите другой размер"), ProductConfigGuard.blockers(c, "u-unknown"))
    }

    @Test fun `other store - volumes without uuid - sale blocked with clear links reason`() {
        val r = cap(storeUuid = "store-fixture-other", key = "cappuccinoOtherStore")
        // ответ другой точки принимается только для запрошенной точки
        assertNull(cap(storeUuid = store, key = "cappuccinoOtherStore").config)
        val c = r.config!!
        assertTrue(c.volumes.all { it.evotorUuid == null && !it.available && it.notReadyReason == "no_store_link" })
        assertEquals(listOf("links"), c.configErrors)
        assertEquals(
            listOf("Объёмы не привязаны к Эвотору на этой точке — продажа запрещена"),
            ProductConfigGuard.blockers(c, "u-cap-200"),
        )
    }

    @Test fun `unavailable volume explains why`() {
        val c = cap().config!!
        val broken = c.copy(volumes = c.volumes.map {
            if (it.evotorUuid == "u-cap-300") it.copy(available = false, notReadyReason = "zero_price") else it
        })
        assertEquals(listOf("У объёма цена 0 ₽ — продать нельзя"), ProductConfigGuard.blockers(broken, "u-cap-300"))
        assertEquals(listOf("Этот объём недоступен — выберите другой размер"), ProductConfigGuard.blockers(
            c.copy(volumes = c.volumes.map { if (it.evotorUuid == "u-cap-300") it.copy(available = false) else it }), "u-cap-300"))
        assertEquals("Объём не выгружен в Эвотор на этой точке — продажа запрещена", ConfigDiagnostics.unavailableText("no_store_link"))
        assertEquals("У объёма нет UUID Эвотора — выгрузите товар заново", ConfigDiagnostics.unavailableText("no_evotor_uuid"))
    }

    @Test fun `espresso - no milk and no additions - plain add allowed`() {
        val r = ProductConfigApi.parse(fx.getJSONObject("espresso").toString(), espId, store)
        val c = r.config!!
        assertNull(c.milk); assertNull(c.additions)
        assertEquals(1, c.volumes.size)
        assertTrue(r.noConfiguration)
        assertTrue(ProductConfigPolicy.silentAddAllowed(r))
    }

    @Test fun `json null for strings is not read as the text null`() {
        // Android optString(JSON null) = "null": uuid и причина не должны стать строкой "null".
        val body = """{"ok":true,"schemaVersion":1,"storeUuid":"S","productId":1,
          "volumes":[{"productId":1,"name":"Эспрессо","label":"Эспрессо","evotorUuid":null,"sizeUnit":null,"available":false,"notReadyReason":null}]}"""
        val v = ProductConfigApi.parse(body, 1, "S").config!!.volumes.single()
        assertNull(v.evotorUuid); assertNull(v.sizeUnit); assertNull(v.notReadyReason)
    }
}
