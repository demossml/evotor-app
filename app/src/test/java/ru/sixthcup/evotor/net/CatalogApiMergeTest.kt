package ru.sixthcup.evotor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.Names
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

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun toppings(n: Int, prefix: String = "Добавка"): List<Topping> =
        (1..n).map { i ->
            Topping(
                id = i,
                name = "$prefix $i",
                priceRub = BigDecimal("30"),
                groupKey = "extra",
                evotorUuid = "top-$i",
            )
        }

    private fun enrich(productId: Int, name: String, tops: List<Topping>) =
        CatalogApi.Enrich(
            productId = productId,
            name = name,
            imageUrl = null,
            recipe = null,
            countsAsCup = true,
            freeEligible = true,
            toppings = tops,
            upsell = emptyList(),
        )

    private fun pos(uuid: String, name: String) = CatalogProduct(
        uuid = uuid,
        name = name,
        priceRub = BigDecimal("250"),
        allowToSell = true,
        recipe = null,
        description = null,
        article = null,
    )

    // ── regression: uuid разошёлся, но имена размеров совпадают ─────────────

    @Test
    fun `size variants merge via exact name when uuid differs`() {
        val e250 = enrich(4, "Капучино 250 мл", toppings(18))
        val e350 = enrich(5, "Капучино 350 мл", toppings(18))
        val e400 = enrich(6, "Капучино 400 мл", toppings(18))
        val payload = CatalogApi.Payload(
            byUuid = mapOf("BE-250" to e250, "BE-350" to e350, "BE-400" to e400),
            upsellDefault = emptyList(),
            // volume-stripped byName для размеров неоднозначен → пусто
            byName = emptyMap(),
            byNameExact = mapOf(
                "капучино 250 мл" to e250,
                "капучино 350 мл" to e350,
                "капучино 400 мл" to e400,
            ),
        )
        val local = listOf(
            pos("LOCAL-250", "Капучино 250 мл"),
            pos("LOCAL-350", "Капучино 350 мл"),
            pos("LOCAL-400", "Капучино 400 мл"),
        )
        val merged = CatalogApi.merge(local, payload)
        assertEquals(3, merged.size)
        merged.forEach {
            assertEquals("размер ${it.name} должен получить 18 toppings", 18, it.toppings.size)
        }
        assertEquals("Капучино 350 мл", merged[1].name)
    }

    @Test
    fun `exact uuid match wins over exact-name fallback`() {
        val e250 = enrich(4, "Капучино 250 мл", toppings(18, "Сироп-250"))
        val e350 = enrich(5, "Капучино 350 мл", toppings(18, "Сироп-350"))
        val payload = CatalogApi.Payload(
            byUuid = mapOf("BE-250" to e250, "BE-350" to e350),
            upsellDefault = emptyList(),
            byName = emptyMap(),
            byNameExact = mapOf("капучино 250 мл" to e250, "капучино 350 мл" to e350),
        )
        // локальный uuid совпадает с backend 350, но имя — «250 мл»: byUuid приоритетнее
        val merged = CatalogApi.merge(listOf(pos("BE-350", "Капучино 250 мл")), payload)
        assertEquals(18, merged.single().toppings.size)
        assertEquals("Сироп-350 1", merged.single().toppings.first().name)
    }

    @Test
    fun `ambiguous name without volume is not glued to another product`() {
        // Два разных товара без объёма: stripped-ключ «молоко» неоднозначен,
        // exact-ключ «молоко» тоже — оба индекса пусты, чужой enrichment не клеится.
        val payload = CatalogApi.Payload(
            byUuid = mapOf(
                "BE-A" to enrich(10, "Молоко", toppings(2, "Доб-1")),
                "BE-B" to enrich(11, "Молоко", toppings(2, "Доб-2")),
            ),
            upsellDefault = emptyList(),
            byName = emptyMap(),
            byNameExact = emptyMap(),
        )
        val merged = CatalogApi.merge(listOf(pos("LOCAL-X", "Молоко")), payload)
        assertEquals(0, merged.single().toppings.size)
        assertFalse(merged.single().supportsMods)
    }

    @Test
    fun `normalizeExact preserves volume while normalize strips it`() {
        assertEquals("капучино 350 мл", Names.normalizeExact("Капучино 350 мл"))
        assertEquals("капучино", Names.normalize("Капучино 350 мл"))
        assertEquals("капучино 250 мл", Names.normalizeExact("Капучино 250 мл"))
        assertTrue(Names.normalizeExact("Капучино 250 мл") != Names.normalizeExact("Капучино 350 мл"))
    }

    // ── group donor: uuid и имя (даже stripped) разошлись, но groupId общий ────

    @Test
    fun `group donor fills toppings when uuid and exact name miss`() {
        val tops = toppings(18)
        val payload = CatalogApi.Payload(
            byUuid = emptyMap(),
            upsellDefault = emptyList(),
            byName = emptyMap(),
            byNameExact = emptyMap(),
            byGroupId = mapOf(2 to tops),
            nameGroupId = mapOf("капучино" to 2),
        )
        // локальные имена без «мл», uuid другие — только stripped-имя ведёт к группе 2
        val local = listOf(
            pos("LOCAL-A", "Капучино"),
            pos("LOCAL-B", "Капучино"),
            pos("LOCAL-C", "Капучино"),
        )
        val merged = CatalogApi.merge(local, payload)
        assertEquals(3, merged.size)
        merged.forEach {
            assertEquals("размер должен получить 18 toppings от донора", 18, it.toppings.size)
            assertEquals(2, it.groupId)
            assertEquals("admin-group:2", it.variantGroup)
        }
    }

    @Test
    fun `matched product with empty toppings borrows from group donor`() {
        val tops = toppings(18)
        val emptyEnrich = enrich(4, "Капучино 250 мл", emptyList()).copy(groupId = 2)
        val payload = CatalogApi.Payload(
            byUuid = mapOf("BE-250" to emptyEnrich),
            upsellDefault = emptyList(),
            byName = emptyMap(),
            byNameExact = emptyMap(),
            byGroupId = mapOf(2 to tops),
            nameGroupId = emptyMap(),
        )
        val merged = CatalogApi.merge(listOf(pos("BE-250", "Капучино 250 мл")), payload)
        assertEquals(18, merged.single().toppings.size)
    }
}
