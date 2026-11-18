package ru.sixthcup.evotor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тексты для кассира на ответ resolve. Различаем только то, что сервер реально различает:
 * тело `{ "error": "<code>" }` и HTTP-статус. Всё остальное — прежние общие формулировки.
 */
class LoyaltyApiErrorTextTest {

    @Test
    fun `blocked card is explained as blocked, not as missing`() {
        val text = LoyaltyApi.resolveErrorText(404, "card_blocked")
        assertEquals("Карта заблокирована.", text)
    }

    @Test
    fun `missing and invalid cards share one clear message`() {
        assertEquals(
            "Карта не найдена. Проверьте номер или отсканируйте QR.",
            LoyaltyApi.resolveErrorText(404, "card_not_found"),
        )
        assertEquals(
            "Карта не найдена. Проверьте номер или отсканируйте QR.",
            LoyaltyApi.resolveErrorText(404, "invalid_card"),
        )
    }

    @Test
    fun `proxy not configured is not reported as a temporary outage`() {
        val text = LoyaltyApi.resolveErrorText(503, "proxy_not_configured")
        assertTrue(text.contains("не настроен"))
        assertFalse(text.contains("временно недоступен"))
    }

    @Test
    fun `generic 5xx still reads as temporary unavailability`() {
        assertEquals("Сервер лояльности временно недоступен (500).", LoyaltyApi.resolveErrorText(500, null))
        assertEquals("Сервер лояльности временно недоступен (502).", LoyaltyApi.resolveErrorText(502, "Bad gateway"))
    }

    @Test
    fun `unknown 404 without a code keeps the previous wording`() {
        assertEquals(
            "Карта не найдена или недействительна. Проверьте номер или QR.",
            LoyaltyApi.resolveErrorText(404, null),
        )
    }

    @Test
    fun `auth and unexpected statuses are clear`() {
        assertEquals("Терминал не авторизован (401). Проверьте привязку кассы в кабинете Эвотор.", LoyaltyApi.resolveErrorText(401, null))
        assertEquals("Неожиданный ответ сервера (418).", LoyaltyApi.resolveErrorText(418, null))
    }

    @Test
    fun `rate limit and reservation conflict are explained`() {
        assertEquals(
            "Слишком много запросов к карте. Подождите минуту и повторите.",
            LoyaltyApi.resolveErrorText(429, null),
        )
        assertEquals(
            "Карта уже используется в другом чеке. Завершите его и повторите.",
            LoyaltyApi.resolveErrorText(409, "reservation_conflict"),
        )
    }

    @Test
    fun `card is dropped on every HTTP failure, not only 400 and 404`() {
        assertTrue(LoyaltyApi.resolveDrop(400))
        assertTrue(LoyaltyApi.resolveDrop(401))
        assertTrue(LoyaltyApi.resolveDrop(403))
        assertTrue(LoyaltyApi.resolveDrop(404))
        assertTrue(LoyaltyApi.resolveDrop(429))
        assertTrue(LoyaltyApi.resolveDrop(500))
        assertTrue(LoyaltyApi.resolveDrop(503))
        // Сетевые исключения сюда не попадают: там остаётся подписанный QR.
        assertFalse(LoyaltyApi.resolveDrop(0))
    }
}
