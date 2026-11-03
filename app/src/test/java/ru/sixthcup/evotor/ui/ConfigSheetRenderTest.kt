package ru.sixthcup.evotor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.data.ProductConfigGuard
import ru.sixthcup.evotor.data.ToppingRules
import ru.sixthcup.evotor.net.ProductConfigApi
import java.math.BigDecimal

/**
 * Прод-кейс: `GET /products/5/configuration` отдаёт 3 объёма, молоко (2 опции) и добавки (17),
 * `configErrors: []`, но в листе на терминале были видны только объёмы.
 *
 * Причина: лист собирал опции только по каталогу (`#mods`, `toppings[]`, `#opt=`), а
 * `optionsFor()` при полной серверной конфигурации даже не вызывался. Проверяется весь путь
 * «JSON → parse → ConfigOptsMapper → опции листа» для выбранного объёма.
 */
class ConfigSheetRenderTest {

    private val store = "20261017-AD69-40F0-80D2-3C027B1A4BF8"

    private fun prodJson(): String {
        val additions = (1..17).joinToString(",") { i ->
            val group = when {
                i <= 8 -> "syrup"
                i <= 11 -> "sugar"
                else -> "extra"
            }
            """{"id":$i,"productId":${900 + i},"name":"Добавка $i","groupKey":"$group",
               "evotorUuid":"ADD-$i","priceRub":${30 + i},"required":false,"maxCount":1,
               "isDefault":false,"zeroOption":false,"available":true,"sendToReceipt":true}"""
        }
        return """
        {"ok":true,"schemaVersion":1,"storeUuid":"$store","productId":5,"generatedAt":1793457343,
         "volumes":[
           {"productId":3,"evotorUuid":"VOL-250","name":"Капучино 250 мл","label":"250 мл","sizeValue":250,"sizeUnit":"ml","priceRub":250,"available":true,"notReadyReason":null},
           {"productId":4,"evotorUuid":"VOL-350","name":"Капучино 350 мл","label":"350 мл","sizeValue":350,"sizeUnit":"ml","priceRub":300,"available":true,"notReadyReason":null},
           {"productId":5,"evotorUuid":"VOL-400","name":"Капучино 400 мл","label":"400 мл","sizeValue":400,"sizeUnit":"ml","priceRub":340,"available":true,"notReadyReason":null}],
         "milk":{"required":true,"byProductId":{
           "3":{"schemeId":1,"options":[
             {"id":1,"label":"Обычное молоко","evotorUuid":null,"isFree":true,"isDefault":true,"sendToReceipt":false,"available":true},
             {"id":2,"productId":2,"label":"Овсяное молоко","evotorUuid":"MILK-OAT","isFree":false,"isDefault":false,"sendToReceipt":true,"available":true}]},
           "4":{"schemeId":1,"options":[
             {"id":1,"label":"Обычное молоко","evotorUuid":null,"isFree":true,"isDefault":true,"sendToReceipt":false,"available":true},
             {"id":2,"productId":2,"label":"Овсяное молоко","evotorUuid":"MILK-OAT","isFree":false,"isDefault":false,"sendToReceipt":true,"available":true}]},
           "5":{"schemeId":1,"options":[
             {"id":1,"label":"Обычное молоко","evotorUuid":null,"isFree":true,"isDefault":true,"sendToReceipt":false,"available":true},
             {"id":2,"productId":2,"label":"Овсяное молоко","evotorUuid":"MILK-OAT","isFree":false,"isDefault":false,"sendToReceipt":true,"available":true}]}}},
         "additions":{"required":false,"byProductId":{
           "3":{"schemeId":1,"options":[$additions]},
           "4":{"schemeId":1,"options":[$additions]},
           "5":{"schemeId":1,"options":[$additions]}}},
         "configErrors":[]}
        """.trimIndent()
    }

    /** Цены берём «из номенклатуры терминала»: всё, что перечислено в конфигурации, есть в кассе. */
    private val priceOf: (String) -> BigDecimal? = { uuid ->
        when {
            uuid.startsWith("VOL-") -> BigDecimal(uuid.removePrefix("VOL-"))
            uuid == "MILK-OAT" -> BigDecimal("70")
            uuid.startsWith("ADD-") -> BigDecimal("40")
            else -> null
        }
    }

    @Test
    fun `prod configuration renders milk and additions for the selected volume`() {
        val cfg = ProductConfigApi.parse(prodJson(), 5, store).config
        assertNotNull("конфигурация с прода должна приниматься", cfg)
        assertEquals(3, cfg!!.volumes.size)
        assertTrue(cfg.configErrors.isEmpty())

        val opts = ConfigOptsMapper.all(cfg, "VOL-400", priceOf)
        assertNotNull("опции должны собираться для объёма 400 мл (productId 5)", opts)
        assertTrue("молоко должно быть в листе", opts!!.milk.size >= 1)
        assertEquals(2, opts.milk.size)
        val additions = opts.syrup.size + opts.sugar.size + opts.extra.size
        assertEquals("все 17 добавок: 8 сироп + 3 сахар + 6 прочее", 17, additions)
    }

    @Test
    fun `every volume gets its own scheme`() {
        val cfg = ProductConfigApi.parse(prodJson(), 5, store).config!!
        for (uuid in listOf("VOL-250", "VOL-350", "VOL-400")) {
            val opts = assertNotNull(ConfigOptsMapper.all(cfg, uuid, priceOf)).let { ConfigOptsMapper.all(cfg, uuid, priceOf)!! }
            assertEquals("молоко для $uuid", 2, opts.milk.size)
            assertEquals("добавки для $uuid", 17, opts.syrup.size + opts.sugar.size + opts.extra.size)
        }
    }

    // Регресс: условие «нужны ли опции» игнорировало конфигурацию, и лист оставался с объёмами.
    @Test
    fun `sheet asks for options when only the server config has them`() {
        assertTrue(
            "конфигурация без меток каталога всё равно даёт опции",
            SheetOptionsPolicy.needed(
                configPresent = true,
                supportsMods = false,
                hasToppings = false,
                hasEvotorOptions = false,
            ),
        )
        assertTrue(SheetOptionsPolicy.needed(true, false, false, false))
        assertTrue(SheetOptionsPolicy.needed(false, true, false, false))
        assertTrue(SheetOptionsPolicy.needed(false, false, true, false))
        assertTrue(SheetOptionsPolicy.needed(false, false, false, true))
        assertFalse("совсем нечего показывать", SheetOptionsPolicy.needed(false, false, false, false))
    }

    // UUID — GUID: регистр не должен решать, найдётся ли схема объёма.
    @Test
    fun `volume uuid match ignores case`() {
        val cfg = ProductConfigApi.parse(prodJson(), 5, store).config!!
        val lower = ConfigOptsMapper.all(cfg, "vol-400", priceOf)
        assertNotNull(lower)
        assertEquals(2, lower!!.milk.size)
    }

    // Обычное молоко не идёт в чек, платное — идёт; обязательное молоко блокирует «В чек».
    @Test
    fun `required milk blocks the receipt until chosen and zero option stays out`() {
        val cfg = ProductConfigApi.parse(prodJson(), 5, store).config!!
        val opts = ConfigOptsMapper.all(cfg, "VOL-400", priceOf)!!

        val blocked = ToppingRules.blockers(ConfigOptsMapper.ruleGroups(opts), emptySet())
        assertTrue("молоко обязательно", blocked.any { it.contains("Молоко") })

        val regular = opts.milk.first { it.label.startsWith("Обычное") }
        assertTrue("обычное молоко — zero-option", regular.zeroOption)
        assertEquals(BigDecimal.ZERO, regular.price)
        assertTrue(ToppingRules.blockers(ConfigOptsMapper.ruleGroups(opts), setOf(regular.key)).isEmpty())

        val oat = opts.milk.first { it.label.startsWith("Овсяное") }
        assertEquals(BigDecimal("70"), oat.price)
        assertEquals("MILK-OAT", oat.evotorUuid)
    }

    @Test
    fun `config with links error blocks the sale with its own text`() {
        val json = prodJson().replace("\"configErrors\":[]", "\"configErrors\":[\"links\"]")
        val cfg = ProductConfigApi.parse(json, 5, store).config!!
        assertNull(ConfigOptsMapper.all(cfg.copy(volumes = cfg.volumes.map { it.copy(evotorUuid = null) }), "VOL-400", priceOf))
        val blocked = ProductConfigGuard.blockers(cfg.copy(volumes = cfg.volumes.map { it.copy(evotorUuid = null) }), "VOL-400")
        assertTrue(blocked.toString(), blocked.first().contains("не привязаны"))
    }
}
