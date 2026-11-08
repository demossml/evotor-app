package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.sixthcup.evotor.net.OrderApi
import java.math.BigDecimal

/**
 * Льгота заказа: берём только подтверждённое backend'ом правило (`benefit` + `quoteTotal`),
 * храним до конца чека и показываем кассиру без пересчёта суммы.
 */
class OrderBenefitTest {

    private fun order(
        benefit: OrderApi.Benefit?,
        quoteTotal: Int? = 350,
    ) = OrderApi.Order(
        id = "order-1",
        shortCode = "123456",
        storeId = 1,
        status = "accepted",
        createdAt = "2026-11-07T10:00:00.000Z",
        expiresAt = "2026-11-07T10:30:00.000Z",
        estimateTotal = 650,
        currency = "RUB",
        cardId = null,
        items = emptyList(),
        warnings = emptyList(),
        benefit = benefit,
        benefitTotal = benefit?.amountRub ?: 0,
        quoteTotal = quoteTotal,
    )

    private fun gift(amount: Int = 300) = OrderApi.Benefit("gift", "Подарок: Капучино 300 мл", amount)

    @Test
    fun `benefit with quote becomes the order rule`() {
        val b = OrderBenefit.from(order(gift()))!!
        assertEquals("gift", b.kind)
        assertEquals("Подарок: Капучино 300 мл", b.title)
        assertEquals(BigDecimal("300.00"), b.amountRub)
        assertEquals("Бонус: Подарок: Капучино 300 мл −300 ₽", b.label())
    }

    @Test
    fun `kind is normalized to lowercase`() {
        val b = OrderBenefit.from(order(OrderApi.Benefit(" CASHBACK ", "Кэшбэк", 50)))!!
        assertEquals("cashback", b.kind)
    }

    @Test
    fun `no benefit means the card path`() {
        assertNull(OrderBenefit.from(order(null)))
    }

    @Test
    fun `benefit without quote total is not applied`() {
        // Backend не подтвердил итог — скидку не выдумываем, работает карточный путь.
        assertNull(OrderBenefit.from(order(gift(), quoteTotal = null)))
    }

    @Test
    fun `zero or negative amount is ignored`() {
        assertNull(OrderBenefit.from(order(gift(amount = 0))))
        assertNull(OrderBenefit.from(order(gift(amount = -10))))
    }

    @Test
    fun `restore reads the session snapshot`() {
        val b = OrderBenefit.restore("voucher", "Ваучер 100 ₽", "100.00")!!
        assertEquals("voucher", b.kind)
        assertEquals(BigDecimal("100.00"), b.amountRub)
        assertNull(OrderBenefit.restore(null, null, "100"))
        assertNull(OrderBenefit.restore("gift", "Подарок", "not-a-number"))
        assertNull(OrderBenefit.restore("gift", "Подарок", "0"))
    }
}
