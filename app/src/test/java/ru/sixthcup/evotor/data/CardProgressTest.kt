package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.LoyaltyApi

/**
 * Симптом «0 из 5» на ручном коде карты.
 *
 * Backend для `kind=code` отдаёт остаток цикла в `paidCups` и обнуляет `freeAvailable`/`cashback`
 * (антифрод: списание только по QR). Значит касса должна: показать прогресс из того, что сервер
 * дал, показать подарок, если он заработан, и **никогда** не включать списание по коду.
 */
class CardProgressTest {

    private fun parse(json: String, code: String = "0012") = LoyaltyApi.parseResolve(org.json.JSONObject(json), code)

    @Test
    fun `manual code with five cups shows full progress and gift, but no redeem`() {
        // Фикстура из задачи: сервер говорит paidCups=5 при цели 5, льготы скрыты.
        val info = parse(
            """{ "ok": true, "cardCode": "12", "kind": "code", "paidCups": 5, "cupsForFree": 5,
                "freeAvailable": 0, "cashback": 0, "freeStatus": "NONE" }""",
        )
        val p = CardProgress.of(info)
        assertEquals(5, p.cups)
        assertEquals(5, p.goal)
        assertTrue("прогресс не должен выглядеть как 0 из 5", p.cups != 0)
        assertTrue("подарок заработан — его надо показать", p.giftAvailable)
        assertFalse("списывать по ручному коду нельзя", p.redeemEnabled)
        assertEquals("code", p.kind)
    }

    @Test
    fun `token with a live reservation can redeem`() {
        val info = parse(
            """{ "ok": true, "cardCode": "12", "paidCups": 0, "cupsForFree": 5, "freeAvailable": 1,
                "cashback": 1500, "freeStatus": "RESERVED", "reservationId": "R-1", "reservationExpiresAt": 9999999999 }""",
            code = "payload.signature",
        )
        val p = CardProgress.of(info, nowSec = 1_000)
        assertTrue(p.redeemEnabled)
        assertTrue(p.giftAvailable)
        assertEquals("token", p.kind)
        assertEquals(1500L, p.cashbackKopecks)
        assertTrue(p.cashbackKnown)
    }

    @Test
    fun `token without reservation cannot redeem`() {
        val info = parse(
            """{ "cardCode": "12", "paidCups": 2, "cupsForFree": 5, "freeAvailable": 1,
                "cashback": 500, "freeStatus": "NONE", "reservationId": null }""",
            code = "payload.signature",
        )
        val p = CardProgress.of(info)
        assertFalse(p.redeemEnabled)
        assertEquals(2, p.cups)
        assertEquals(3, p.left)
    }

    @Test
    fun `expired reservation does not allow redeem`() {
        val info = parse(
            """{ "paidCups": 0, "cupsForFree": 5, "freeAvailable": 1, "reservationId": "R-1",
                "reservationExpiresAt": 1000 }""",
            code = "payload.signature",
        )
        // 1000 + grace(120) < 2000 — резерв просрочен.
        assertFalse(CardProgress.of(info, nowSec = 2000).redeemEnabled)
        // В пределах допуска — ещё можно.
        assertTrue(CardProgress.of(info, nowSec = 1100).redeemEnabled)
    }

    @Test
    fun `true zero stays zero`() {
        val info = parse("""{ "paidCups": 0, "cupsForFree": 5, "freeAvailable": 0, "cashback": 0, "freeStatus": "NONE" }""")
        val p = CardProgress.of(info)
        assertEquals(0, p.cups)
        assertEquals(5, p.left)
        assertFalse(p.giftAvailable)
        assertFalse(p.giftKnown)
    }

    @Test
    fun `paidTotal gives exact progress even when paidCups is a cycle remainder`() {
        // Так отвечает backend сейчас на ручной код: paidCups = paid_total % cupsForFree = 0.
        val info = parse(
            """{ "paidCups": 0, "cupsForFree": 5, "freeAvailable": 0, "cashback": 0,
                "paidTotal": 7, "freeUsed": 1, "freeStatus": "NONE" }""",
        )
        val p = CardProgress.of(info)
        assertEquals(2, p.cups)          // 7 % 5
        // 7/5 = 1 заработан, 1 уже использован → свободных подарков нет.
        assertFalse(p.giftAvailable)
        assertTrue(p.giftKnown)          // но прогресс известен точно
        assertFalse(p.redeemEnabled)
    }

    @Test
    fun `paidTotal with unused gift reports an available gift`() {
        val info = parse(
            """{ "paidCups": 0, "cupsForFree": 5, "freeAvailable": 0, "cashback": 0,
                "paidTotal": 10, "freeUsed": 1, "freeStatus": "NONE" }""",
        )
        val p = CardProgress.of(info)
        assertEquals(0, p.cups)          // 10 % 5 — начало нового цикла
        assertTrue(p.giftAvailable)      // 10/5 = 2 заработано, 1 использован
        assertFalse(p.redeemEnabled)     // всё равно только по QR
    }

    @Test
    fun `known aliases are parsed`() {
        val info = parse(
            """{ "cups": 3, "cups_for_free": 6, "free_available": 0, "cashback_kopecks": 0,
                "paid_total": 3, "free_used": 0 }""",
        )
        assertEquals(3, info.paidCups)
        assertEquals(6, info.cupsForFree)
        assertEquals(3, info.paidTotal)
        assertEquals(0, info.freeUsed)
    }

    @Test
    fun `cashback is marked unknown for a redacted manual response`() {
        val p = CardProgress.of(parse("""{ "paidCups": 1, "cupsForFree": 5, "cashback": 0 }"""))
        assertFalse(p.cashbackKnown)
        assertFalse(p.redeemEnabled)
    }

    @Test
    fun `progress survives the session round trip`() {
        val info = parse("""{ "paidCups": 0, "cupsForFree": 5, "paidTotal": 5, "freeUsed": 0 }""")
        assertEquals(5, info.paidTotal)
        assertEquals(0, info.freeUsed)
        // CardProgress пересчитывается из сохранённых полей — прогресс не теряется после рестарта.
        assertEquals(0, CardProgress.of(info).cups)
        assertTrue(CardProgress.of(info).giftAvailable)
    }
}
