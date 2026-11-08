package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.OrderApi
import java.math.BigDecimal

/**
 * Единый пуллер предзаказа: панель заказа и авто-подтягивание по QR дают один и тот же план.
 */
class PreorderPullerTest {

    private fun product(uuid: String, name: String = "Капучино 250 мл", price: String = "190") = CatalogProduct(
        uuid = uuid, name = name, priceRub = BigDecimal(price), allowToSell = true,
        recipe = null, description = null, article = null, freeEligible = false,
    )

    private fun item(lineId: String, uuid: String?, name: String = "Капучино 250 мл") = OrderApi.Item(
        lineId = lineId,
        productId = "p-$lineId",
        evotorProductUuid = uuid,
        name = name,
        quantity = 1,
        volumeLabel = "250 мл",
        configText = "250 мл",
        unitPriceEstimate = 190,
        modifiers = emptyList(),
    )

    private fun order(
        status: String = "ready",
        items: List<OrderApi.Item> = listOf(item("l1", "U1")),
        benefit: OrderApi.Benefit? = null,
        quote: Int? = null,
    ) = OrderApi.Order(
        id = "order-1",
        shortCode = "279874",
        storeId = 1,
        status = status,
        createdAt = "2026-11-08T09:00:00.000Z",
        expiresAt = "2026-11-08T09:30:00.000Z",
        estimateTotal = 190,
        currency = "RUB",
        cardId = null,
        items = items,
        warnings = emptyList(),
        benefit = benefit,
        benefitTotal = benefit?.amountRub ?: 0,
        quoteTotal = quote,
    )

    private val catalog = listOf(product("U1"))

    @Test
    fun `ready order becomes cart lines with the order benefit`() {
        val p = PreorderPuller.prepare(
            order(benefit = OrderApi.Benefit("gift", "Подарок", 50), quote = 140),
            catalog,
        )
        assertEquals(1, p.plan.added)
        assertTrue(p.canSilentPull)
        assertEquals("50.00", p.benefit!!.amountRub.toPlainString())
        assertEquals(1, p.plan.lines.size)
    }

    @Test
    fun `item without terminal uuid is not pulled silently`() {
        val p = PreorderPuller.prepare(order(items = listOf(item("l1", null, "Сезонный раф"))), catalog)
        assertFalse(p.canSilentPull)
        assertTrue(p.hasIssues)
        assertEquals(listOf("Сезонный раф"), p.plan.missingUuid)
    }

    @Test
    fun `product missing in the terminal is not pulled silently`() {
        val p = PreorderPuller.prepare(order(items = listOf(item("l1", "GONE"))), catalog)
        assertFalse(p.canSilentPull)
        assertEquals(listOf("Капучино 250 мл"), p.plan.notFound)
    }

    @Test
    fun `repeat scan sees the lines already in the cart`() {
        val o = order()
        val first = PreorderPuller.prepare(o, catalog)
        val second = PreorderPuller.prepare(o, catalog, first.plan.lines.map { it.cartKey }.toSet())
        assertFalse(second.plan.hasAnything)
        assertEquals(listOf("Капучино 250 мл"), second.plan.alreadyPresent)
        assertTrue("повтор не ошибка, ведём к оплате", second.canSilentPull)
    }

    @Test
    fun `order items are kept for the session when nothing is added`() {
        val p = PreorderPuller.prepare(order(), catalog)
        assertEquals(listOf(PreorderPullSession.Item("U1", "Капучино 250 мл", 1)), p.orderItems)
    }
}
