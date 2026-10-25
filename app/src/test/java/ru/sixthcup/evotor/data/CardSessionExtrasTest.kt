package ru.sixthcup.evotor.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.BuildConfig

/** Контракт extras.sc: v≥2, c, op, free, cb в копейках, ts в миллисекундах, kind, ключ appId. */
class CardSessionExtrasTest {

    private fun card(
        code: String = "1234567890",
        cardCode: String = "1234567890",
        reservationId: String? = "res-1",
    ) = CardInfo(
        code = code,
        cardCode = cardCode,
        paidCups = 2,
        cupsForFree = 5,
        freeAvailable = 1,
        cashbackKopecks = 1500,
        reservationId = reservationId,
        reservationExpiresAt = 0L,
        freeStatus = "RESERVED",
    )

    @Test
    fun `extras nested under app uuid and flat sc are identical`() {
        val extras = CardSession.extras(card(), freeApplied = true, cashbackKopecks = 500)
        val nested = extras.getJSONObject(BuildConfig.APP_UUID).getJSONObject("sc")
        val flat = extras.getJSONObject("sc")
        assertEquals(flat.toString(), nested.toString())
    }

    @Test
    fun `sc matches backend contract`() {
        val before = System.currentTimeMillis()
        val sc = CardSession.sc(card(cardCode = "0001234"), freeApplied = true, cashbackKopecks = 700)
        assertTrue(sc.getInt("v") >= 2)
        assertEquals("0001234", sc.getString("c"))
        assertEquals("res-1", sc.getString("op"))
        assertEquals(1, sc.getInt("free"))
        assertEquals(700, sc.getInt("cb"))
        assertEquals("code", sc.getString("kind"))
        assertTrue(sc.getLong("ts") >= before)
    }

    @Test
    fun `signed qr is reported as token and code is used when cardCode is empty`() {
        val sc = CardSession.sc(card(code = "payload.signature", cardCode = "", reservationId = null), freeApplied = false, cashbackKopecks = 0)
        assertEquals("token", sc.getString("kind"))
        assertEquals("payload.signature", sc.getString("c"))
        assertEquals(0, sc.getInt("free"))
        assertFalse(sc.has("op"))
    }
}
