package ru.sixthcup.evotor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.Topping
import java.math.BigDecimal

class CatalogApiMergeTest {
    @Test
    fun `uses unique normalized product name when backend UUID is missing or different`() {
        val posProduct = CatalogProduct(
            uuid = "evotor-pos-uuid",
            name = "Капучино",
            priceRub = BigDecimal("250"),
            allowToSell = true,
            recipe = null,
            description = null,
            article = null,
        )
        val topping = Topping(
            id = 7,
            name = "Ваниль",
            priceRub = BigDecimal("30"),
            groupKey = "syrup",
            evotorUuid = "evotor-vanilla-uuid",
        )
        val enrichment = CatalogApi.Enrich(
            productId = 101,
            name = "Капучино",
            imageUrl = null,
            recipe = null,
            countsAsCup = true,
            freeEligible = true,
            toppings = listOf(topping),
            upsell = emptyList(),
        )
        val merged = CatalogApi.merge(
            listOf(posProduct),
            CatalogApi.Payload(
                byUuid = emptyMap(),
                upsellDefault = emptyList(),
                byName = mapOf("капучино" to enrichment),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(1, merged.single().toppings.size)
        assertEquals("Ваниль", merged.single().toppings.single().name)
        assertTrue(merged.single().supportsMods)
    }

    @Test
    fun `exact UUID mapping remains preferred over name fallback`() {
        val posProduct = CatalogProduct(
            uuid = "uuid-1", name = "Капучино",
            priceRub = BigDecimal("250"), allowToSell = true,
            recipe = null, description = null, article = null,
        )
        val byUuid = CatalogApi.Enrich(
            productId = 1, name = "Капучино", imageUrl = null, recipe = null,
            countsAsCup = true, freeEligible = true,
            toppings = listOf(Topping(1, "UUID сироп", BigDecimal("20"), "syrup", evotorUuid = "u1")),
            upsell = emptyList(),
        )
        val byName = byUuid.copy(toppings = listOf(Topping(2, "fallback сироп", BigDecimal("10"), "syrup", evotorUuid = "u2")))
        val merged = CatalogApi.merge(
            listOf(posProduct),
            CatalogApi.Payload(mapOf("uuid-1" to byUuid), emptyList(), byName = mapOf("капучино" to byName)),
        )
        assertEquals("UUID сироп", merged.single().toppings.single().name)
    }
}
