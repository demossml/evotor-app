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
        seq: Int? = null,
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
        seq = seq,
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

    @Test
    fun `sc q is omitted when the server did not send a sequence`() {
        val sc = CardSession.sc(card(seq = null), freeApplied = true, cashbackKopecks = 500)
        assertFalse(sc.has("q"))
        // Отсутствие q не мешает собрать полноценный чек.
        assertEquals("1234567890", sc.getString("c"))
        assertEquals(1, sc.getInt("free"))
    }

    @Test
    fun `sc q is passed through when the sequence is known`() {
        val sc = CardSession.sc(card(seq = 42), freeApplied = false, cashbackKopecks = 0)
        assertEquals(42, sc.getInt("q"))
        // Остальные поля на месте: q не заменяет и не ломает контракт.
        assertEquals("1234567890", sc.getString("c"))
        assertEquals("res-1", sc.getString("op"))
    }

    @Test
    fun `zero sequence is treated as unknown`() {
        val sc = CardSession.sc(card(seq = 0), freeApplied = false, cashbackKopecks = 0)
        assertFalse(sc.has("q"))
    }

    @Test
    fun `queue order id and number are written into sc`() {
        val q = QueueNumber(orderId = "ord-123", publicNumber = "47")
        val sc = CardSession.sc(card(), freeApplied = true, cashbackKopecks = 500, queue = q)
        assertEquals("ord-123", sc.getString("ord"))
        assertEquals("47", sc.getString("qn"))
        // остальные поля не изменились
        assertEquals("1234567890", sc.getString("c"))
        assertEquals("res-1", sc.getString("op"))
        assertEquals(1, sc.getInt("free"))
    }

    @Test
    fun `no queue number means no ord or qn keys`() {
        val sc = CardSession.sc(card(), freeApplied = false, cashbackKopecks = 0, queue = null)
        assertFalse(sc.has("ord"))
        assertFalse(sc.has("qn"))
        // старый чек обрабатывается как раньше
        assertEquals("1234567890", sc.getString("c"))
        assertEquals(2, sc.getInt("v"))
    }

    @Test
    fun `stale queue number is not written`() {
        val old = QueueNumber(
            orderId = "ord-old",
            publicNumber = "9",
            issuedAt = System.currentTimeMillis() - QueueNumber.MAX_AGE_MS - 1_000L,
        )
        val sc = CardSession.sc(card(), freeApplied = false, cashbackKopecks = 0, queue = old)
        assertFalse(sc.has("ord"))
        assertFalse(sc.has("qn"))
    }

    // ── voucherId: аддитивное поле, только QR + резерв ──────────────────────────────────────

    @Test
    fun `voucher id goes into sc only with a live qr reservation`() {
        val sc = CardSession.sc(
            card(code = "payload.signature", cardCode = "0012", reservationId = "R-1"),
            freeApplied = false, cashbackKopecks = 0, voucherId = 12,
        )
        assertEquals(12, sc.getInt("voucherId"))
        assertEquals("R-1", sc.getString("op"))
    }

    @Test
    fun `voucher id is dropped without a reservation`() {
        val sc = CardSession.sc(
            card(code = "payload.signature", cardCode = "", reservationId = null),
            freeApplied = false, cashbackKopecks = 0, voucherId = 12,
        )
        assertFalse(sc.has("voucherId"))
    }

    @Test
    fun `voucher id is never sent with a manual numeric code`() {
        val sc = CardSession.sc(card(code = "000 12", cardCode = "12"), freeApplied = false, cashbackKopecks = 0, voucherId = 12)
        assertEquals("code", sc.getString("kind"))
        assertFalse(sc.has("voucherId"))
        assertEquals(0, sc.getInt("free"))
        assertEquals(0L, sc.getLong("cb"))
    }

    @Test
    fun `no voucher id key when no voucher was chosen`() {
        val sc = CardSession.sc(card(), freeApplied = true, cashbackKopecks = 500)
        assertFalse(sc.has("voucherId"))
    }

    @Test
    fun `voucher and cashback never travel together in sc`() {
        // Модель состояния уже не даёт выбрать оба: ваучер обнуляет кэшбэк.
        val choice = Choice.normalize(free = false, cashback = true, cashbackKopecks = 5000L, voucherId = 12)
        val sc = CardSession.sc(
            card(code = "payload.signature", cardCode = "0012", reservationId = "R-1"), freeApplied = false,
            cashbackKopecks = choice.cashbackKopecks, voucherId = choice.voucherId,
        )
        assertEquals(12, sc.getInt("voucherId"))
        assertEquals(0L, sc.getLong("cb"))
    }

    @Test
    fun `voucher id is identical in flat and nested sc`() {
        val extras = CardSession.extras(
            card(code = "payload.signature", cardCode = "0012", reservationId = "R-1"),
            freeApplied = false, cashbackKopecks = 0, voucherId = 7,
        )
        val flat = extras.getJSONObject("sc")
        val nested = extras.getJSONObject(BuildConfig.APP_UUID).getJSONObject("sc")
        assertEquals(7, flat.getInt("voucherId"))
        assertEquals(flat.toString(), nested.toString())
    }
}
