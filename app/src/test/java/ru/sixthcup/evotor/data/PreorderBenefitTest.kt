package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.OrderApi

/**
 * Бонус гостя в панели заказа (контракт v2, п.1.7).
 *
 * APK скидку не считает: показывает оценку, подпись бонуса и итог от backend.
 * Без `quoteTotal` итог — оценка, строка бонуса не рисуется.
 */
class PreorderBenefitTest {

    private fun benefit(kind: String, title: String, amount: Int) =
        OrderApi.Benefit(kind = kind, title = title, amountRub = amount)

    @Test
    fun `gift benefit shows title and amount`() {
        val m = PreorderQueue.money(650, benefit("gift", "Подарок: Капучино 300 мл", 300), 300, 350)
        assertTrue(m.hasBenefit)
        assertEquals("Бонус: Подарок: Капучино 300 мл −300 ₽", m.benefitLine)
        assertEquals(350, m.totalRub)
        assertEquals(
            "Оценка гостя: 650 ₽ · бонус «Подарок: Капучино 300 мл» −300 ₽ · итог 350 ₽",
            m.summary,
        )
    }

    @Test
    fun `voucher and cashback keep their backend title`() {
        val voucher = PreorderQueue.money(400, benefit("voucher", "Ваучер 100 ₽", 100), 100, 300)
        assertEquals("Бонус: Ваучер 100 ₽ −100 ₽", voucher.benefitLine)
        assertEquals(300, voucher.totalRub)
        val cashback = PreorderQueue.money(400, benefit("cashback", "Кэшбэк", 50), 50, 350)
        assertEquals("Бонус: Кэшбэк −50 ₽", cashback.benefitLine)
    }

    @Test
    fun `kind label is a fallback for empty title`() {
        assertEquals("Подарок", PreorderQueue.benefitKindLabel("gift"))
        assertEquals("Ваучер", PreorderQueue.benefitKindLabel("voucher"))
        assertEquals("Кэшбэк", PreorderQueue.benefitKindLabel("cashback"))
        assertEquals("Бонус", PreorderQueue.benefitKindLabel(null))
        val m = PreorderQueue.money(650, benefit("gift", "", 0), 300, 350)
        assertEquals("Бонус: Подарок −300 ₽", m.benefitLine)
    }

    @Test
    fun `null benefit draws no line`() {
        val m = PreorderQueue.money(650, null, 0, null)
        assertFalse(m.hasBenefit)
        assertNull(m.benefitLine)
        assertEquals(650, m.totalRub)
        assertEquals("Оценка гостя: 650 ₽", m.summary)
    }

    @Test
    fun `null quote total falls back to the estimate without a benefit line`() {
        val m = PreorderQueue.money(650, benefit("gift", "Подарок", 300), 300, null)
        assertFalse("без итога бонус не показываем", m.hasBenefit)
        assertNull(m.benefitLine)
        assertEquals(650, m.totalRub)
        assertEquals(650, PreorderQueue.totalRub(650, null))
        assertEquals(350, PreorderQueue.totalRub(650, 350))
    }

    @Test
    fun `benefit conflict has a cashier-facing text`() {
        assertEquals(
            "Бонус гостя уже применён в другом чеке",
            PreorderQueue.errorText("benefit_conflict", 409),
        )
    }
}
