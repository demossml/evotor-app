package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.BuildConfig

/**
 * Контракт extras.sc: v≥2, c, op, free, cb в копейках, ts в миллисекундах, kind, ключ appId.
 *
 * `c` — то, по чему backend определяет способ списания: подписанный QR-токен (тогда он может
 * потреблять резерв) или цифровой код карты (тогда только начисление). Это и был дефект L-00:
 * в `c` уходил cardCode, поэтому подарок и кэшбэк никогда не списывались.
 */
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
    fun `qr session puts the signed token in sc c, not the card code`() {
        val before = System.currentTimeMillis()
        val sc = CardSession.sc(
            card(code = "payload.signature", cardCode = "0012"),
            freeApplied = true,
            cashbackKopecks = 700,
        )
        assertTrue(sc.getInt("v") >= 2)
        assertEquals("payload.signature", sc.getString("c"))
        assertEquals("token", sc.getString("kind"))
        assertEquals("res-1", sc.getString("op"))
        assertEquals(1, sc.getInt("free"))
        assertEquals(700, sc.getInt("cb"))
        assertTrue(sc.getLong("ts") >= before)
    }

    @Test
    fun `manual card code stays numeric and never pretends to be a token`() {
        // resolve отдал канонический код без ведущих нулей, гость ввёл с нулями и пробелом.
        val sc = CardSession.sc(card(code = "000 12", cardCode = "12"), freeApplied = false, cashbackKopecks = 0)
        assertEquals("12", sc.getString("c"))
        assertEquals("code", sc.getString("kind"))
        assertEquals(0, sc.getInt("free"))
    }

    @Test
    fun `regression - token in session is never replaced by card code`() {
        // Именно это ломал коммит 57a5c5f: cardCode.ifBlank { code } как единственная ветка.
        val sc = CardSession.sc(
            card(code = "eyJpZCI6ImFiYyJ9.c2ln", cardCode = "1234567890"),
            freeApplied = false,
            cashbackKopecks = 0,
        )
        assertEquals("eyJpZCI6ImFiYyJ9.c2ln", sc.getString("c"))
        assertFalse(sc.getString("c").all { it.isDigit() })
    }

    @Test
    fun `no op key without an active reservation`() {
        val sc = CardSession.sc(
            card(code = "payload.signature", cardCode = "", reservationId = null),
            freeApplied = false,
            cashbackKopecks = 0,
        )
        assertEquals("payload.signature", sc.getString("c"))
        assertEquals("token", sc.getString("kind"))
        assertFalse(sc.has("op"))
    }

    @Test
    fun `offline qr without resolve still sends the token`() {
        // LoyaltyApi.offline(): cardCode пуст, code — токен, резерва нет, льгот нет.
        val sc = CardSession.sc(
            card(code = "payload.signature", cardCode = "", reservationId = null),
            freeApplied = false,
            cashbackKopecks = 0,
        )
        assertEquals("payload.signature", sc.getString("c"))
        assertEquals(0, sc.getInt("free"))
    }

    @Test
    fun `benefits are only claimed together with the reservation id`() {
        // Резерва нет -> op не пишем, а free/cb не могут выглядеть выданными.
        val sc = CardSession.sc(
            card(code = "payload.signature", cardCode = "", reservationId = null),
            freeApplied = true,
            cashbackKopecks = 700,
        )
        assertFalse(sc.has("op"))
        assertEquals(0, sc.getInt("free"))
        assertEquals(0L, sc.getLong("cb"))
    }

    @Test
    fun `reservation id is written as op and kept in both extras forms`() {
        val extras = CardSession.extras(card(reservationId = "R-abc123"), freeApplied = true, cashbackKopecks = 500)
        val flat = extras.getJSONObject("sc")
        val nested = extras.getJSONObject(BuildConfig.APP_UUID).getJSONObject("sc")
        assertEquals("R-abc123", flat.getString("op"))
        assertEquals("R-abc123", nested.getString("op"))
        assertEquals(1, flat.getInt("free"))
        assertEquals(500L, flat.getLong("cb"))
    }
}
