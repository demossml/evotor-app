package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формат печати зафиксирован спецификацией §3.4. Проверяем ровно её примеры:
 * 1) номер + QR + прогресс; 2) применена бесплатная чашка; 3) ручной код (прогресс скрыт);
 * 4) номера нет, карта есть; 5) печатать нечего.
 */
class ReceiptPrintTextTest {

    private fun queue(n: String = "47", ageMs: Long = 0L) = QueueNumber(
        orderId = "ord-1",
        publicNumber = n,
        issuedAt = System.currentTimeMillis() - ageMs,
    )

    private fun loyalty(
        cardFound: Boolean = true,
        current: Int = 4,
        target: Int = 5,
        known: Boolean = true,
        earned: Int = 0,
        reward: Boolean = false,
        title: String? = null,
    ) = SalePrintLoyalty(cardFound, current, target, known, earned, reward, title)

    @Test
    fun `example 1 - number, qr and known progress`() {
        assertEquals(
            listOf("ЗАКАЗ № 47", "Лояльность: 4 из 5"),
            ReceiptPrintText.lines(queue(), loyalty()),
        )
    }

    @Test
    fun `example 2 - free cup applied`() {
        assertEquals(
            listOf("ЗАКАЗ № 47", "Лояльность: 0 из 5", "Бесплатный кофе"),
            ReceiptPrintText.lines(queue(), loyalty(current = 0, reward = true, title = "Бесплатный кофе")),
        )
    }

    @Test
    fun `example 3 - manual code hides progress`() {
        assertEquals(
            listOf("ЗАКАЗ № 47", "Лояльность: карта принята"),
            ReceiptPrintText.lines(queue(), loyalty(current = 0, known = false)),
        )
    }

    @Test
    fun `example 4 - no number but card present`() {
        assertEquals(
            listOf("Лояльность: 4 из 5"),
            ReceiptPrintText.lines(null, loyalty()),
        )
    }

    @Test
    fun `example 5 - nothing to print means empty list`() {
        assertTrue(ReceiptPrintText.lines(null, loyalty(cardFound = false, current = 0, target = 0, known = false)).isEmpty())
    }

    @Test
    fun `cashback line is printed for cashback only`() {
        assertEquals(
            listOf("ЗАКАЗ № 47", "Лояльность: 4 из 5", "Кэшбэк"),
            ReceiptPrintText.lines(queue(), loyalty(reward = true, title = "Кэшбэк")),
        )
    }

    @Test
    fun `earned cups line only when the number is really known`() {
        assertEquals(
            listOf("ЗАКАЗ № 47", "Лояльность: 4 из 5", "Начислено: +1"),
            ReceiptPrintText.lines(queue(), loyalty(earned = 1)),
        )
    }

    @Test
    fun `no invented zero progress and no invented earned line`() {
        val lines = ReceiptPrintText.lines(queue(), loyalty(current = 0, known = false, earned = 0))
        assertEquals(listOf("ЗАКАЗ № 47", "Лояльность: карта принята"), lines)
        assertTrue(lines.none { it.contains("0 из 5") })
        assertTrue(lines.none { it.contains("Начислено") })
    }

    @Test
    fun `reward without a known title adds no invented wording`() {
        assertEquals(
            listOf("ЗАКАЗ № 47", "Лояльность: 4 из 5"),
            ReceiptPrintText.lines(queue(), loyalty(reward = true, title = null)),
        )
    }

    @Test
    fun `stale number is not printed`() {
        assertTrue(
            ReceiptPrintText.lines(
                queue(ageMs = QueueNumber.MAX_AGE_MS + 1_000L),
                loyalty(cardFound = false, current = 0, target = 0, known = false),
            ).isEmpty(),
        )
    }

    @Test
    fun `no personal data on the receipt`() {
        val text = ReceiptPrintText.lines(
            queue(),
            loyalty(reward = true, title = "Бесплатный кофе"),
        ).joinToString(" ")
        assertTrue(text.contains("ЗАКАЗ № 47"))
        // Полный orderId, телефон и номер карты не печатаются.
        assertTrue(!text.contains("ord-1"))
        assertTrue(text.split(" ").none { w -> w.length > 4 && w.all { it.isDigit() } })
    }
}
