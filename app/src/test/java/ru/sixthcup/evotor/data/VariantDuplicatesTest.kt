package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.CatalogApi
import java.io.File
import java.math.BigDecimal

/**
 * Регрессия: подтверждённая карточка «Капучино» с выбором объёма существует,
 * но исходные позиции объёмов остаются отдельными плитками главного меню.
 *
 * Реальный сценарий с кассы: у вариантов есть своя (слабая) группа либо группы нет,
 * поэтому они не считаются членами подтверждённой карточки.
 */
class VariantDuplicatesTest {

    private fun drink(
        uuid: String,
        name: String,
        price: Int,
        variantGroup: String? = null,
        sizeMl: Int? = null,
        groupName: String? = "Кофе",
    ) = CatalogProduct(
        uuid = uuid,
        name = name,
        priceRub = BigDecimal(price),
        allowToSell = true,
        recipe = null,
        description = null,
        article = null,
        groupName = groupName,
        variantGroup = variantGroup,
        sizeMl = sizeMl,
        supportsMods = true,
    )

    private fun confirmedGroup() = listOf(
        drink("cap200", "Капучино 200 мл", 200, "admin-group:5", 200),
        drink("cap300", "Капучино 300 мл", 250, "admin-group:5", 300),
        drink("cap400", "Капучино 400 мл", 300, "admin-group:5", 400),
    )

    /** Строки, которые дублируют те же объёмы, но со своей группой (по одной позиции). */
    private fun strayCopies() = listOf(
        drink("stray200", "Капучино 200 мл", 200, "admin-group:101", 200),
        drink("stray300", "Капучино 300 мл", 250, "admin-group:102", 300),
        drink("stray400", "Капучино 400 мл", 300, "admin-group:103", 400),
    )

    @Test
    fun `parent card plus duplicate volume rows render one tile`() {
        val model = DrinkVariants.build(confirmedGroup() + strayCopies())
        assertEquals("на главном экране должна быть одна плитка", 1, model.tiles.size)
        val tile = model.tiles.single()
        assertEquals("Капучино", tile.name)
        assertEquals(3, model.variantsOf(tile).size)
        assertEquals(listOf(200, 300, 400), model.variantsOf(tile).map { it.sizeMl })
    }

    @Test
    fun `each volume keeps its own sku and price inside the parent card`() {
        val model = DrinkVariants.build(confirmedGroup() + strayCopies())
        val variants = model.variantsOf(model.tiles.single())
        assertEquals(listOf("cap200", "cap300", "cap400"), variants.map { it.uuid })
        assertEquals(listOf(200, 250, 300), variants.map { it.priceRub.toInt() })
        assertTrue(variants.all { it.allowToSell })
    }

    @Test
    fun `new volume from a stray row is attached to the parent card`() {
        val base = listOf(
            drink("cap200", "Капучино 200 мл", 200, "admin-group:5", 200),
            drink("cap300", "Капучино 300 мл", 250, "admin-group:5", 300),
        )
        val extra = drink("stray400", "Капучино 400 мл", 300, "admin-group:103", 400)
        val model = DrinkVariants.build(base + extra)
        assertEquals(1, model.tiles.size)
        val variants = model.variantsOf(model.tiles.single())
        assertEquals(listOf(200, 300, 400), variants.map { it.sizeMl })
        assertEquals("stray400", variants.last().uuid)
    }

    @Test
    fun `two independent drinks with the same base name in different groups are not merged`() {
        val model = DrinkVariants.build(
            listOf(
                drink("a200", "Раф 200 мл", 200, "admin-group:5", 200),
                drink("a300", "Раф 300 мл", 250, "admin-group:5", 300),
                drink("b200", "Раф 200 мл", 210, "admin-group:6", 200),
                drink("b300", "Раф 300 мл", 260, "admin-group:6", 300),
            ),
        )
        assertEquals(2, model.tiles.size)
    }

    @Test
    fun `lone size-labelled product without a parent stays visible`() {
        val model = DrinkVariants.build(listOf(drink("solo", "Капучино 300 мл", 250, null, 300)))
        assertEquals(1, model.tiles.size)
        assertEquals("Капучино 300 мл", model.tiles.single().name)
    }

    @Test
    fun `duplicates do not reappear after reading the catalog from offline cache`() {
        val payload = CatalogApi.Payload(
            byUuid = mapOf(
                "cap200" to enrich("cap200", 5),
                "cap300" to enrich("cap300", 5),
                "cap400" to enrich("cap400", 5),
                "stray200" to enrich("stray200", 101),
                "stray300" to enrich("stray300", 102),
                "stray400" to enrich("stray400", 103),
            ),
            upsellDefault = emptyList(),
        )
        val file = File.createTempFile("variants-cache", ".json")
        try {
            CatalogCache.saveTo(file, payload)
            val cached = CatalogCache.loadFrom(file)!!
            // локальная номенклатура без групп — всё берётся из кэша
            val terminal = (confirmedGroup() + strayCopies()).map {
                it.copy(variantGroup = null, groupName = null)
            }
            val merged = CatalogApi.merge(terminal, cached)
            val model = DrinkVariants.build(merged)
            assertEquals(1, model.tiles.size)
            assertEquals(3, model.variantsOf(model.tiles.single()).size)
        } finally {
            file.delete()
        }
    }

    private fun enrich(uuid: String, groupId: Int) = CatalogApi.Enrich(
        productId = uuid.hashCode(),
        name = null,
        imageUrl = null,
        recipe = null,
        countsAsCup = true,
        freeEligible = true,
        toppings = emptyList(),
        upsell = emptyList(),
        groupId = groupId,
    )
}
