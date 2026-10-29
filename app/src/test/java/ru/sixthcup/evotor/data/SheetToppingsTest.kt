package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Смена объёма не должна убирать молоко/сироп, если добавки есть у другого размера этой же карточки.
 */
class SheetToppingsTest {

    private val milk = Topping(id = 1, name = "Овсяное", priceRub = BigDecimal("40"), groupKey = "milk", evotorUuid = "u-oat")
    private val plain = Topping(id = 2, name = "Обычное", priceRub = BigDecimal.ZERO, groupKey = "milk", zeroOption = true, isDefault = true)
    private val syrup = Topping(id = 3, name = "Ваниль", priceRub = BigDecimal("30"), groupKey = "syrup", evotorUuid = "u-van")
    private val group = listOf(plain, milk, syrup)

    private fun size(uuid: String, ml: Int, group: String? = "admin-group:1", tops: List<Topping> = emptyList()) = CatalogProduct(
        uuid = uuid, name = "Капучино $ml мл", priceRub = BigDecimal("200"), allowToSell = true,
        recipe = null, description = null, article = null,
        variantGroup = group, sizeMl = ml, toppings = tops,
    )

    @Test
    fun `selected size without toppings takes them from a sibling of the same card`() {
        val s250 = size("a", 250, tops = group)
        val s350 = size("b", 350)
        val s450 = size("c", 450)
        val variants = listOf(s250, s350, s450)

        val tops = SheetToppings.forSheet(s450, variants)

        assertEquals(group, tops)
        assertTrue(tops.any { it.groupKey == "milk" })
        assertTrue(tops.any { it.groupKey == "syrup" })
    }

    @Test
    fun `switching 250 to 450 and back keeps the milk section source`() {
        val variants = listOf(size("a", 250, tops = group), size("b", 350), size("c", 450))
        for (selected in variants) {
            assertTrue("size ${selected.sizeMl}", SheetToppings.forSheet(selected, variants).any { it.groupKey == "milk" })
        }
    }

    @Test
    fun `own toppings win over the donor`() {
        val own = listOf(milk)
        val s250 = size("a", 250, tops = group)
        val s350 = size("b", 350, tops = own)
        assertEquals(own, SheetToppings.forSheet(s350, listOf(s250, s350)))
    }

    @Test
    fun `a donor from another card is never used`() {
        val other = size("x", 250, group = "admin-group:2", tops = group)
        val selected = size("c", 450, group = "admin-group:1")
        assertEquals(emptyList<Topping>(), SheetToppings.forSheet(selected, listOf(other, selected)))
    }

    @Test
    fun `no toppings anywhere stays empty`() {
        val variants = listOf(size("a", 250), size("b", 350))
        assertEquals(emptyList<Topping>(), SheetToppings.forSheet(variants[1], variants))
    }

    @Test
    fun `effective returns the same instance when the size has its own toppings`() {
        val s = size("a", 250, tops = group)
        assertSame(s, SheetToppings.effective(s, listOf(s)))
    }

    @Test
    fun `effective carries donor toppings but keeps the selected size identity and price`() {
        val s250 = size("a", 250, tops = group)
        val s450 = size("c", 450).copy(priceRub = BigDecimal("300"))
        val eff = SheetToppings.effective(s450, listOf(s250, s450))
        assertEquals("c", eff.uuid)
        assertEquals(BigDecimal("300"), eff.priceRub)
        assertEquals(group, eff.toppings)
    }
}
