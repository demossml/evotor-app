package ru.sixthcup.evotor.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт kitchen create (`POST /api/devices/kitchen/orders`) и разбор ответов.
 * Сеть не нужна: проверяем тело запроса и терпимость к вариантам ответа backend.
 */
class KitchenApiTest {

    private val line = KitchenApi.Line(
        name = "Капучино 350 мл",
        qty = 2,
        mods = listOf("Овсяное", "Ванильный"),
        note = "без сахара",
    )

    @Test
    fun `body carries contract fields`() {
        val body = KitchenApi.body(
            clientOrderId = "c-1",
            storeUuid = "s-1",
            lines = listOf(line),
            totalKopecks = 45_000,
            guestUserId = 77,
            cardCodeMask = "0007",
            queueOrderId = "q-1",
        )
        assertEquals("c-1", body.getString("clientOrderId"))
        assertEquals("s-1", body.getString("storeUuid"))
        assertEquals(45_000L, body.getLong("totalKopecks"))
        assertEquals(77L, body.getLong("guestUserId"))
        assertEquals("0007", body.getString("cardCodeMask"))
        assertEquals("q-1", body.getString("queueOrderId"))

        val item = body.getJSONArray("items").getJSONObject(0)
        assertEquals("Капучино 350 мл", item.getString("name"))
        assertEquals(2, item.getInt("qty"))
        assertEquals(2, item.getJSONArray("mods").length())
        assertEquals("без сахара", item.getString("note"))
    }

    @Test
    fun `body omits optional fields when missing`() {
        val body = KitchenApi.body("c-1", "s-1", listOf(KitchenApi.Line("Эспрессо", 1)))
        assertFalse(body.has("totalKopecks"))
        assertFalse(body.has("guestUserId"))
        assertFalse(body.has("cardCodeMask"))
        assertFalse(body.has("queueOrderId"))
        assertFalse(body.getJSONArray("items").getJSONObject(0).has("note"))
    }

    @Test
    fun `blank mods are dropped and qty is at least one`() {
        val body = KitchenApi.body("c", "s", listOf(KitchenApi.Line("Эспрессо", 0, listOf("", " "))))
        val item = body.getJSONArray("items").getJSONObject(0)
        assertEquals(1, item.getInt("qty"))
        assertEquals(0, item.getJSONArray("mods").length())
    }

    @Test
    fun `created response is parsed`() {
        val created = KitchenApi.parseCreated(
            JSONObject("""{"id":"k-1","queueNumber":7,"status":"new","createdAt":"2026-11-22T10:00:00Z"}"""),
        )
        assertNotNull(created)
        assertEquals("k-1", created!!.id)
        assertEquals("7", created.queueNumber)
        assertEquals("new", created.status)
    }

    @Test
    fun `created response without id or number is rejected`() {
        assertNull(KitchenApi.parseCreated(JSONObject("""{"queueNumber":7}""")))
        assertNull(KitchenApi.parseCreated(JSONObject("""{"id":"k-1"}""")))
        assertNull(KitchenApi.parseCreated(JSONObject("""{"id":"","queueNumber":""}""")))
    }

    @Test
    fun `statuses parse from array`() {
        val list = KitchenApi.parseStatuses("""[{"id":"k-1","queueNumber":"7","status":"done"}]""")
        assertEquals(1, list?.size)
        assertEquals("done", list!![0].status)
        assertTrue(KitchenApi.isDone(list[0].status))
    }

    @Test
    fun `statuses parse from wrapper objects`() {
        assertEquals(1, KitchenApi.parseStatuses("""{"orders":[{"id":"a","status":"seen"}]}""")?.size)
        assertEquals(1, KitchenApi.parseStatuses("""{"data":[{"id":"a","status":"seen"}]}""")?.size)
        assertEquals(1, KitchenApi.parseStatuses("""{"order":{"id":"a","status":"seen"}}""")?.size)
        assertEquals(1, KitchenApi.parseStatuses("""{"id":"a","status":"seen"}""")?.size)
    }

    @Test
    fun `statuses tolerate broken payloads`() {
        assertNull(KitchenApi.parseStatuses(""))
        assertNull(KitchenApi.parseStatuses("not json"))
        assertEquals(0, KitchenApi.parseStatuses("""[{"queueNumber":"1"}]""")?.size)
    }

    @Test
    fun `done normalizes case and synonyms`() {
        assertTrue(KitchenApi.isDone("DONE"))
        assertTrue(KitchenApi.isDone(" ready "))
        assertTrue(KitchenApi.isDone("completed"))
        assertFalse(KitchenApi.isDone("new"))
        assertFalse(KitchenApi.isDone(null))
    }
}
