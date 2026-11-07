package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ChoicePrintTest {

    private fun line(name: String, label: String?, qty: Int = 1, title: String? = "Корица") = CartLine(
        productUuid = "u", name = name, priceRub = BigDecimal("200"), quantity = BigDecimal(qty),
        choiceLabel = label, choiceTitle = title,
    )

    private fun queue() = QueueNumber(orderId = "o", publicNumber = "47", issuedAt = System.currentTimeMillis())

    @Test fun `entries only for lines with a choice`() {
        val e = ChoicePrint.entriesFrom(listOf(line("Капучино", "С корицей"), line("Эспрессо", null), line("Раф", "  ")))
        assertEquals(1, e.size)
        assertEquals("Капучино", e[0].drink)
    }

    @Test fun `line text is drink title and label`() {
        val e = ChoicePrint.entriesFrom(listOf(line("Капучино 250", "С корицей")))
        assertEquals(listOf("Капучино 250 — Корица: С корицей"), ChoicePrint.lines(e))
    }

    @Test fun `quantity is shown when more than one`() {
        val e = ChoicePrint.entriesFrom(listOf(line("Капучино", "Без корицы", qty = 2)))
        assertEquals(listOf("Капучино — Корица: Без корицы ×2"), ChoicePrint.lines(e))
    }

    @Test fun `long text wraps within printer width`() {
        val e = ChoicePrint.entriesFrom(listOf(line("Капучино на кокосовом молоке большой", "С корицей", qty = 3)))
        val out = ChoicePrint.lines(e)
        assertTrue(out.size >= 2)
        assertTrue(out.all { it.length <= ChoicePrint.WIDTH })
        assertEquals(
            "Капучино на кокосовом молоке большой — Корица: С корицей ×3",
            out.joinToString(" ") { it.trim() },
        )
    }

    @Test fun `very long word is split not dropped`() {
        val out = ChoicePrint.wrap("а".repeat(70), 32)
        assertTrue(out.all { it.length <= 32 })
        assertEquals(70, out.joinToString("") { it.trim() }.length)
    }

    @Test fun `receipt text puts choice after queue number and before loyalty`() {
        val loyalty = SalePrintLoyalty(true, 4, 5, true)
        val entries = ChoicePrint.entriesFrom(listOf(line("Капучино", "С корицей")))
        val out = ReceiptPrintText.lines(queue(), loyalty, entries)
        assertEquals("ЗАКАЗ № 47", out.first())
        assertEquals("Капучино — Корица: С корицей", out[1])
        assertEquals("Лояльность: 4 из 5", out[2])
    }

    @Test fun `without choice the receipt text is unchanged`() {
        val loyalty = SalePrintLoyalty(true, 4, 5, true)
        assertEquals(
            ReceiptPrintText.lines(queue(), loyalty),
            ReceiptPrintText.lines(queue(), loyalty, emptyList()),
        )
        assertEquals(listOf("ЗАКАЗ № 47", "Лояльность: 4 из 5"), ReceiptPrintText.lines(queue(), loyalty))
    }

    @Test fun `choice prints even without queue number`() {
        val entries = ChoicePrint.entriesFrom(listOf(line("Капучино", "С корицей")))
        assertEquals(listOf("Капучино — Корица: С корицей"), ReceiptPrintText.lines(null, null, entries))
    }

    @Test fun `store json round trip and garbage tolerance`() {
        val e = ChoicePrint.entriesFrom(listOf(line("Капучино", "С корицей", qty = 2)))
        assertEquals(e, ChoicePrintStore.decode(ChoicePrintStore.encode(e)))
        assertTrue(ChoicePrintStore.decode(null).isEmpty())
        assertTrue(ChoicePrintStore.decode("not json").isEmpty())
    }
}
