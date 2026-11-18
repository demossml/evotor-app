package ru.sixthcup.evotor.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone-сценарий кассы: ответ resolve без QR/устройства.
 * Backend отдаёт cardCode и canRedeem=true — этого достаточно для списания кэшбэка.
 */
class LoyaltyApiPhoneResolveTest {

    @Test
    fun `phone resolve takes card code from response and keeps PHONE marker`() {
        val info = LoyaltyApi.parseResolve(
            JSONObject(
                """{
                  "ok": true,
                  "cardCode": "0012",
                  "kind": "code",
                  "canRedeem": true,
                  "balanceKopecks": 5000,
                  "cashback": 5000,
                  "paidCups": 2,
                  "cupsForFree": 5,
                  "freeAvailable": 0,
                  "freeStatus": "NONE",
                  "phoneMasked": "+7 ••• ••• 45 67"
                }""",
            ),
            code = "",
            identifiedBy = "PHONE",
        )

        assertEquals("0012", info.code)
        assertEquals("0012", info.cardCode)
        assertEquals("PHONE", info.identifiedBy)
        assertEquals("code", info.kind)
        assertTrue(info.canRedeem)
        assertEquals(5000L, info.cashbackKopecks)
        assertEquals(5000L, info.balanceKopecks)
    }
}
