package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.CatalogApi
import java.math.BigDecimal

/** S3 research: local names diverge, volume from #group=, toppings from payload byGroupId. */
class VolumeToppingsFallbackTest {

    private val milk = Topping(
        id = 1, name = "Овсяное", priceRub = BigDecimal("50"),
        groupKey = "milk", evotorUuid = "u-milk", zeroOption = false, available = true,
    )
    private val ordinary = Topping(
        id = -11, name = "Обычное", priceRub = BigDecimal.ZERO,
        groupKey = "milk", evotorUuid = null, zeroOption = true, isDefault = true, available = true,
    )

    private fun enrich(name: String, gid: Int) = CatalogApi.Enrich(
        productId = 4, name = name, imageUrl = null, recipe = null,
        countsAsCup = true, freeEligible = true,
        toppings = listOf(ordinary, milk),
        upsell = emptyList(), groupId = gid,
    )

    @Test
    fun looseNameGroupId_mapsExtraTokenToServerKey() {
        val map = mapOf("капучино" to 2)
        assertEquals(2, Names.looseNameGroupId("капучино", map))
        assertEquals(2, Names.looseNameGroupId("капучино m", map))
        assertEquals(null, Names.looseNameGroupId("латте", map))
    }

    @Test
    fun merge_groupDonor_whenLocalNameHasExtraToken() {
        val local = listOf(
            CatalogProduct(
                uuid = "local-a", name = "Капучино M 250 мл",
                priceRub = BigDecimal("220"), allowToSell = true,
                recipe = null, description = "#group=cappuccino", article = null,
                variantGroup = "cappuccino", sizeMl = 250,
            ),
            CatalogProduct(
                uuid = "local-b", name = "Капучино M 350 мл",
                priceRub = BigDecimal("250"), allowToSell = true,
                recipe = null, description = "#group=cappuccino", article = null,
                variantGroup = "cappuccino", sizeMl = 350,
            ),
        )
        val en = enrich("Капучино 250 мл", 2)
        val payload = CatalogApi.Payload(
            byUuid = emptyMap(),
            upsellDefault = emptyList(),
            byNameExact = mapOf(Names.normalizeExact("Капучино 250 мл") to en),
            byGroupId = mapOf(2 to listOf(ordinary, milk)),
            nameGroupId = mapOf("капучино" to 2),
        )
        val merged = CatalogApi.merge(local, payload)
        assertTrue(merged.all { it.toppings.isNotEmpty() })
        assertTrue(merged.all { it.toppings.any { t -> t.groupKey == "milk" } })
    }

    @Test
    fun sheetToppings_payloadFallback_whenVariantsHaveNoTops() {
        val selected = CatalogProduct(
            uuid = "x", name = "Капучино 400 мл",
            priceRub = BigDecimal("280"), allowToSell = true,
            recipe = null, description = null, article = null,
            variantGroup = "cappuccino", sizeMl = 400,
        )
        val payload = CatalogApi.Payload(
            byUuid = emptyMap(),
            upsellDefault = emptyList(),
            byGroupId = mapOf(2 to listOf(ordinary, milk)),
            nameGroupId = mapOf("капучино" to 2),
        )
        val tops = SheetToppings.forSheet(selected, listOf(selected), payload)
        assertEquals(2, tops.size)
        assertTrue(tops.any { it.groupKey == "milk" })
    }
}
