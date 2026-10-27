package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что печатается на фискальном чеке: номер заказа и краткий блок лояльности.
 * Тексты — только цифры и служебные слова, без персональных данных.
 */
class ReceiptPrintTextTest {

    private fun queue(n: String = "47", ageMs: Long = 0L) = QueueNumber(
        orderId = "ord-1",
        publicNumber = n,
        issuedAt = System.currentTimeMillis() - ageMs,
    )

    @Test
    fun `sale without a card prints only the order number`() {
        val lines = ReceiptPrintText.lines(queue(), SalePrintLoyalty(cardFound = false, progressCurrent = 0, progressTarget = 0, progressKnown = false))
        assertEquals(listOf("ЗАКАЗ № 47"), lines)
    }

    @Test
    fun `identified customer gets number and progress`() {
        val lines = ReceiptPrintText.lines(
            queue(),
            SalePrintLoyalty(cardFound = true, progressCurrent = 4, progressTarget = 5, progressKnown = true),
        )
        assertEquals(listOf("ЗАКАЗ № 47", "Лояльность: 4 из 5"), lines)
    }

    @Test
    fun `reward applied is printed explicitly`() {
        val lines = ReceiptPrintText.lines(
            queue(),
            SalePrintLoyalty(
                cardFound = true,
                progressCurrent = 0,
                progressTarget = 5,
                progressKnown = true,
                rewardApplied = true,
                rewardTitle = "Бесплатный кофе",
            ),
        )
        assertEquals(listOf("ЗАКАЗ № 47", "Лояльность: 0 из 5", "Бесплатный кофе"), lines)
    }

    @Test
    fun `earned cups line appears only when known`() {
        val lines = ReceiptPrintText.lines(
            queue(),
            SalePrintLoyalty(cardFound = true, progressCurrent = 2, progressTarget = 5, progressKnown = true, cupsEarnedThisSale = 1),
        )
        assertEquals(listOf("ЗАКАЗ № 47", "Лояльность: 2 из 5", "Начислено: +1"), lines)
    }

    @Test
    fun `manual code without trusted progress prints no fake zeros`() {
        val lines = ReceiptPrintText.lines(
            queue(),
            SalePrintLoyalty(cardFound = true, progressCurrent = 0, progressTarget = 5, progressKnown = false),
        )
        assertEquals(listOf("ЗАКАЗ № 47", "Лояльность: карта принята"), lines)
    }

    @Test
    fun `no number and no card means nothing to print`() {
        assertTrue(
            ReceiptPrintText.lines(
                null,
                SalePrintLoyalty(cardFound = false, progressCurrent = 0, progressTarget = 0, progressKnown = false),
            ).isEmpty(),
        )
    }

    @Test
    fun `stale number is not printed`() {
        val lines = ReceiptPrintText.lines(
            queue(ageMs = QueueNumber.MAX_AGE_MS + 1_000L),
            SalePrintLoyalty(cardFound = false, progressCurrent = 0, progressTarget = 0, progressKnown = false),
        )
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `text contains no personal data`() {
        val lines = ReceiptPrintText.lines(
            queue(),
            SalePrintLoyalty(cardFound = true, progressCurrent = 3, progressTarget = 5, progressKnown = true, rewardApplied = true),
        ).joinToString(" ")
        assertTrue(lines.contains("ЗАКАЗ №"))
        // Номер карты, телефон и имя на чек не попадают.
        assertTrue(!lines.contains("ord-1"))
        assertTrue(lines.split(" ").none { w -> w.length > 4 && w.all { ch -> ch.isDigit() } })
    }
}
