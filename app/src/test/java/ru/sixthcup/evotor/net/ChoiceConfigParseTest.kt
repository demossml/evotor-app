package ru.sixthcup.evotor.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.data.ProductConfigCache
import ru.sixthcup.evotor.data.ProductConfigGuard
import ru.sixthcup.evotor.data.VolumeRef

/**
 * `choices` в ответе конфигурации — настоящие ответы backend (файл product-configuration-choice-fixture.json
 * собран из его фикстур). `schemaVersion` остаётся 1: ключ только добавлен.
 */
class ChoiceConfigParseTest {

    private val fx = JSONObject(
        javaClass.classLoader!!.getResourceAsStream("product-configuration-choice-fixture.json")!!
            .bufferedReader().use { it.readText() },
    )
    private val cap get() = fx.getJSONObject("cappuccino")
    private val esp get() = fx.getJSONObject("espresso")
    private val store = "store-config-fixture"

    private fun parse(o: JSONObject, pid: Int) = ProductConfigApi.parse(o.toString(), pid, store)

    @Test fun `schema version is still one`() {
        assertEquals(1, cap.getInt("schemaVersion"))
        assertEquals(1, ProductConfigApi.SCHEMA_VERSION)
    }

    @Test fun `cappuccino choice parses with title and two options`() {
        val c = parse(cap, cap.getInt("productId")).config!!
        val ch = c.choice!!
        assertTrue(ch.required)
        val scheme = ch.byProductId.values.first()
        assertEquals("Корица", scheme.title)
        assertEquals(listOf("С корицей", "Без корицы"), scheme.options.map { it.label })
        assertFalse(scheme.invalid)
        assertTrue(c.hasChoices)
    }

    @Test fun `choice is resolved strictly for the volume`() {
        val c = parse(cap, cap.getInt("productId")).config!!
        val v = c.volumes.first()
        val s = ProductConfigGuard.choiceScheme(c, VolumeRef(evotorUuid = v.evotorUuid))
        assertNotNull(s)
        assertEquals("Корица", s!!.title)
        assertNull(ProductConfigGuard.choiceScheme(c, VolumeRef(evotorUuid = "unknown")))
    }

    @Test fun `espresso without scheme has no choice and keeps silent add`() {
        val r = parse(esp, esp.getInt("productId"))
        assertNull(r.config!!.choice)
        assertTrue(r.noConfiguration)
        assertTrue(ProductConfigPolicy.silentAddAllowed(r))
    }

    @Test fun `choice only configuration is not silent`() {
        val body = JSONObject(esp.toString())
            .put("choices", JSONObject().put("required", true).put("byProductId", JSONObject()
                .put("7", JSONObject().put("schemeId", 1).put("title", "Корица").put("options", org.json.JSONArray()
                    .put(JSONObject().put("id", 1).put("label", "С корицей").put("isDefault", false))
                    .put(JSONObject().put("id", 2).put("label", "Без корицы").put("isDefault", false))))))
        val r = parse(body, body.getInt("productId"))
        assertFalse(r.noConfiguration)
        assertFalse(ProductConfigPolicy.silentAddAllowed(r))
    }

    @Test fun `volumes without scheme stay empty and do not block`() {
        val body = JSONObject(cap.toString())
        val by = body.getJSONObject("choices").getJSONObject("byProductId")
        val firstKey = by.keys().next()
        val empty = JSONObject().put("schemeId", JSONObject.NULL).put("title", "").put("options", org.json.JSONArray())
        by.put(firstKey, empty)
        val c = parse(body, body.getInt("productId")).config!!
        val first = c.volumes.first { it.productId == firstKey.toInt() }
        assertNull(ProductConfigGuard.choiceScheme(c, VolumeRef(evotorUuid = first.evotorUuid)))
        assertTrue(ProductConfigGuard.blockers(c, VolumeRef(evotorUuid = first.evotorUuid)).none { it.contains("Выбор") })
    }

    @Test fun `invalid scheme blocks the sale`() {
        val body = JSONObject(cap.toString())
        val by = body.getJSONObject("choices").getJSONObject("byProductId")
        val key = by.keys().next()
        by.getJSONObject(key).put("error", "invalid_scheme").put("options", org.json.JSONArray())
        val c = parse(body, body.getInt("productId")).config!!
        val v = c.volumes.first { it.productId == key.toInt() }
        val blockers = ProductConfigGuard.blockers(c, VolumeRef(evotorUuid = v.evotorUuid))
        assertTrue(blockers.any { it.contains("Корица") && it.contains("неверно") })
    }

    @Test fun `scheme with fewer than two options is invalid`() {
        val body = JSONObject(cap.toString())
        val by = body.getJSONObject("choices").getJSONObject("byProductId")
        val key = by.keys().next()
        val one = org.json.JSONArray().put(JSONObject().put("id", 1).put("label", "С корицей").put("isDefault", false))
        by.getJSONObject(key).put("options", one)
        val c = parse(body, body.getInt("productId")).config!!
        assertTrue(c.choice!!.byProductId[key.toInt()]!!.invalid)
    }

    @Test fun `json null label and title never become the text null`() {
        val body = JSONObject(cap.toString())
        val by = body.getJSONObject("choices").getJSONObject("byProductId")
        val key = by.keys().next()
        val s = by.getJSONObject(key)
        s.put("title", JSONObject.NULL)
        s.getJSONArray("options").put(JSONObject().put("id", 9).put("label", JSONObject.NULL).put("isDefault", false))
        val c = parse(body, body.getInt("productId")).config!!
        val parsed = c.choice!!.byProductId[key.toInt()]!!
        assertEquals("", parsed.title)
        assertTrue(parsed.options.none { it.label == "null" || it.id == 9 })
    }

    @Test fun `old response without choices key parses as before`() {
        val body = JSONObject(cap.toString())
        body.remove("choices")
        val c = parse(body, body.getInt("productId")).config!!
        assertNull(c.choice)
        assertNotNull(c.milk)
    }

    @Test fun `cache round trip keeps the choice`() {
        val c = parse(cap, cap.getInt("productId")).config!!
        val saved = ProductConfigCache.toJson(c).getJSONObject("config")
        val back = ProductConfigApi.parse(saved.toString(), c.productId, store).config!!
        assertEquals(c.choice, back.choice)
    }
}
