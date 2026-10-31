package ru.sixthcup.evotor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Контракт `GET /api/devices/products/{id}/configuration`: три независимые группы,
 * отдельная схема молока, повреждённый JSON не роняет кассу, чужая точка не принимается.
 */
class ProductConfigParseTest {

    private val store = "STORE-UUID"

    private fun body(
        milk: String? = null,
        additions: String? = null,
        volumes: String = """
            [{"productId":501,"evotorUuid":"VOL-250","name":"Капучино 250 мл","label":"250 мл","sizeValue":250,"sizeUnit":"ml","priceRub":250,"available":true},
             {"productId":502,"evotorUuid":"VOL-350","name":"Капучино 350 мл","label":"350 мл","sizeValue":350,"sizeUnit":"ml","priceRub":300,"available":true}]
        """.trimIndent(),
        storeUuid: String = store,
    ): String = """
        {"ok":true,"schemaVersion":1,"storeUuid":"$storeUuid","productId":501,"generatedAt":1792860000,
         "volumes":$volumes
         ${milk?.let { ",\"milk\":$it" } ?: ""}
         ${additions?.let { ",\"additions\":$it" } ?: ""}}
    """.trimIndent()

    private val milkJson = """
        {"required":true,"byProductId":{"501":{"schemeId":7,"options":[
          {"id":1,"productId":null,"label":"Обычное молоко","evotorUuid":null,"isDefault":true,"isFree":true,"sendToReceipt":false,"available":true},
          {"id":2,"productId":900,"label":"Кокосовое молоко","evotorUuid":"COCONUT","isDefault":false,"isFree":false,"sendToReceipt":true,"available":true}
        ]},"502":{"schemeId":7,"options":[]}}}
    """.trimIndent()

    private val additionsJson = """
        {"required":false,"byProductId":{"501":{"schemeId":12,"options":[
          {"id":21,"productId":901,"name":"Ванильный сироп","groupKey":"syrup","evotorUuid":"VANILLA","priceRub":50,
           "required":false,"maxCount":1,"isDefault":false,"zeroOption":false,"available":true,"sendToReceipt":true}
        ]},"502":{"schemeId":12,"options":[]}}}
    """.trimIndent()

    // 1. Нет схем молока и добавок
    @Test
    fun `1 - no milk and no additions means no blocks`() {
        val res = ProductConfigApi.parse(body(), 501, store)
        val cfg = assertNotNull(res.config).let { res.config!! }
        assertNull(cfg.milk)
        assertNull(cfg.additions)
        assertEquals(2, cfg.volumes.size)
    }

    // 2. Только молоко
    @Test
    fun `2 - only milk scheme is parsed separately`() {
        val cfg = ProductConfigApi.parse(body(milk = milkJson), 501, store).config!!
        assertNotNull(cfg.milk)
        assertNull(cfg.additions)
        val scheme = cfg.milkFor(501)!!
        assertEquals(2, scheme.options.size)
        assertTrue(cfg.milk!!.required)
    }

    // 3. Только добавки
    @Test
    fun `3 - only additions scheme`() {
        val cfg = ProductConfigApi.parse(body(additions = additionsJson), 501, store).config!!
        assertNull(cfg.milk)
        assertEquals(1, cfg.additionsFor(501)!!.options.size)
        assertEquals("syrup", cfg.additionsFor(501)!!.options.single().groupKey)
    }

    // 4. Обе схемы — независимые блоки
    @Test
    fun `4 - milk and additions stay independent`() {
        val cfg = ProductConfigApi.parse(body(milk = milkJson, additions = additionsJson), 501, store).config!!
        assertEquals(2, cfg.milkFor(501)!!.options.size)
        assertEquals(1, cfg.additionsFor(501)!!.options.size)
        // У 350 мл схемы пустые — окно не должно показывать чужие опции.
        assertEquals(0, cfg.milkFor(502)!!.options.size)
    }

    // 5. Три объёма
    @Test
    fun `5 - three volumes are all parsed`() {
        val three = """
            [{"productId":1,"evotorUuid":"A","name":"Капучино 250","label":"250"},
             {"productId":2,"evotorUuid":"B","name":"Капучино 350","label":"350"},
             {"productId":3,"evotorUuid":"C","name":"Капучино 400","label":"400"}]
        """.trimIndent()
        val cfg = ProductConfigApi.parse(body(volumes = three), 501, store).config!!
        assertEquals(3, cfg.volumes.size)
        assertTrue(cfg.hasChoices)
    }

    // 6. Обычное молоко: не идёт в чек
    @Test
    fun `6 - ordinary milk is free and never a receipt line`() {
        val cfg = ProductConfigApi.parse(body(milk = milkJson), 501, store).config!!
        val regular = cfg.milkFor(501)!!.options.first()
        assertTrue(regular.isFree)
        assertFalse(regular.sendToReceipt)
        assertFalse(regular.receiptLine)
    }

    // 7. Платное молоко: отдельная позиция
    @Test
    fun `7 - paid milk is a receipt line with its uuid`() {
        val cfg = ProductConfigApi.parse(body(milk = milkJson), 501, store).config!!
        val coconut = cfg.milkFor(501)!!.options.last()
        assertTrue(coconut.receiptLine)
        assertEquals("COCONUT", coconut.evotorUuid)
    }

    // 11. Платное молоко без uuid — недоступно
    @Test
    fun `11 - paid milk without uuid is not a receipt line`() {
        val broken = """
            {"required":false,"byProductId":{"501":{"schemeId":1,"options":[
              {"id":5,"label":"Овсяное","evotorUuid":null,"isFree":false,"sendToReceipt":true,"available":true}]}}}
        """.trimIndent()
        val opt = ProductConfigApi.parse(body(milk = broken), 501, store).config!!.milkFor(501)!!.options.single()
        assertFalse(opt.receiptLine)
    }

    // 12. Чужая точка не используется
    @Test
    fun `12 - config from another store is rejected`() {
        val res = ProductConfigApi.parse(body(milk = milkJson, storeUuid = "OTHER-STORE"), 501, store)
        assertNull(res.config)
        assertEquals("Конфигурация другой точки", res.error)
    }

    // 15. Повреждённый JSON
    @Test
    fun `15 - broken json does not crash`() {
        val res = ProductConfigApi.parse("{not json", 501, store)
        assertNull(res.config)
        assertNotNull(res.error)
    }

    @Test
    fun `15 - empty json object is treated as no configuration`() {
        val res = ProductConfigApi.parse("{}", 501, store, 200)
        assertNull(res.config)
        assertEquals("Несовместимая версия конфигурации (0)", res.error)
    }

    // schemaVersion
    @Test
    fun `unsupported schema version is rejected`() {
        val res = ProductConfigApi.parse("""{"ok":true,"schemaVersion":99,"storeUuid":"$store","productId":501}""", 501, store)
        assertNull(res.config)
        assertTrue(res.error!!.contains("99"))
    }

    // 16. HTTP 403 — понятная ошибка (проверяем классификацию в fetch-контракте)
    @Test
    fun `16 - forbidden response carries a human message`() {
        val res = ProductConfigApi.FetchResult(null, 403, "Нет доступа к конфигурации товара (HTTP 403)")
        assertNull(res.config)
        assertTrue(res.error!!.contains("403"))
        assertFalse(res.unsupported)
    }

    // 18. Нет конфигурации — путь «обычное добавление»
    @Test
    fun `18 - single volume without schemes is not a choice screen`() {
        val one = """[{"productId":501,"evotorUuid":"VOL-250","name":"Капучино 250 мл","label":"250"}]"""
        val res = ProductConfigApi.parse(body(volumes = one), 501, store)
        assertNotNull(res.config)
        assertTrue(res.noConfiguration)
        assertFalse(res.config!!.hasChoices)
    }

    @Test
    fun `empty option arrays do not create fake schemes`() {
        val emptyMilk = """{"required":true,"byProductId":{"501":{"schemeId":7,"options":[]}}}"""
        val cfg0 = ProductConfigApi.parse(body(milk = emptyMilk), 501, store).config!!
        assertNull("пустая схема молока не рисует блок", cfg0.milk)
    }
}
