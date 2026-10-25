package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ReceiptValidatorTest {

    private fun product(
        uuid: String,
        toppings: List<Topping> = emptyList(),
        hide: Boolean = false,
    ) = CatalogProduct(
        uuid = uuid,
        name = "item-$uuid",
        priceRub = BigDecimal("150.00"),
        allowToSell = true,
        recipe = null,
        description = null,
        article = null,
        toppings = toppings,
        hideFromMenu = hide,
    )

    private fun topping(id: Int, uuid: String?, price: String = "50", available: Boolean = true) = Topping(
        id = id,
        name = "top-$id",
        priceRub = BigDecimal(price),
        groupKey = "extra",
        evotorUuid = uuid,
        available = available,
    )

    private fun line(uuid: String, optionId: Int? = null) = CartLine(
        productUuid = uuid,
        name = "line-$uuid",
        priceRub = BigDecimal("50.00"),
        optionId = optionId,
    )

    @Test
    fun `valid receipt has no issues`() {
        val top = topping(7, "top-uuid")
        val products = listOf(product("drink-uuid", listOf(top)), product("top-uuid", hide = true))
        val issues = ReceiptValidator.validate(listOf(line("drink-uuid"), line("top-uuid", 7)), products)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `product deleted in evotor blocks the line`() {
        val issues = ReceiptValidator.validate(listOf(line("gone")), listOf(product("drink-uuid")))
        assertEquals(1, issues.size)
        assertEquals("product_not_found", issues.first().reason)
    }

    @Test
    fun `paid option without evotor uuid is refused`() {
        val broken = topping(7, null)
        val products = listOf(product("drink-uuid", listOf(broken)), product("top-uuid"))
        val issues = ReceiptValidator.validate(listOf(line("top-uuid", 7)), products)
        assertEquals("option_without_uuid", issues.first().reason)
    }

    @Test
    fun `option removed from scheme blocks the line`() {
        val products = listOf(product("drink-uuid"), product("top-uuid"))
        val issues = ReceiptValidator.validate(listOf(line("top-uuid", 99)), products)
        assertEquals("option_removed", issues.first().reason)
    }

    @Test
    fun `unavailable option blocks the line`() {
        val off = topping(7, "top-uuid", available = false)
        val products = listOf(product("drink-uuid", listOf(off)), product("top-uuid", hide = true))
        val issues = ReceiptValidator.validate(listOf(line("top-uuid", 7)), products)
        assertEquals("option_unavailable", issues.first().reason)
    }

    @Test
    fun `option re-linked to another product blocks the line`() {
        val moved = topping(7, "new-top-uuid")
        val products = listOf(product("drink-uuid", listOf(moved)), product("old-top-uuid", hide = true))
        val issues = ReceiptValidator.validate(listOf(line("old-top-uuid", 7)), products)
        assertEquals("option_changed", issues.first().reason)
    }

    @Test
    fun `synthetic cart line is never sent`() {
        val issues = ReceiptValidator.validate(listOf(line("mod:1")), emptyList())
        assertEquals("no_evotor_product", issues.first().reason)
    }
}
