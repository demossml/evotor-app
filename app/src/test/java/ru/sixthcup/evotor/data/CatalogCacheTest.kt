package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.CatalogApi
import java.io.File
import java.math.BigDecimal

/**
 * Офлайн-кэш не должен «разжаловать» скрытую добавку в обычную плитку:
 * флаги скрытия, артикул, id категории и группы вариантов обязаны пережить запись/чтение.
 */
class CatalogCacheTest {

    private fun enrich(hide: Boolean, categoryId: Int?) = CatalogApi.Enrich(
        productId = 42,
        name = "Карамель",
        imageUrl = null,
        recipe = null,
        countsAsCup = false,
        freeEligible = false,
        toppings = emptyList(),
        upsell = emptyList(),
        hideFromMenu = hide,
        categoryId = categoryId,
        articleNumber = "sc-42",
    )

    @Test
    fun `cache keeps hidden flags, article, category and variant groups`() {
        val file = File.createTempFile("catalog-cache", ".json")
        try {
            val payload = CatalogApi.Payload(
                byUuid = mapOf("u-42" to enrich(hide = true, categoryId = 4)),
                upsellDefault = emptyList(),
                byName = mapOf("карамель" to enrich(hide = true, categoryId = 4)),
                addonCategoryIds = setOf(4),
                groupByProductId = mapOf(77 to 5),
                groupByUuid = mapOf("u-77" to 5),
            )
            CatalogCache.saveTo(file, payload)
            val back = CatalogCache.loadFrom(file)!!

            val restored = back.byUuid["u-42"]!!
            assertTrue("hideFromMenu должен сохраниться", restored.hideFromMenu)
            assertEquals(4, restored.categoryId)
            assertEquals("sc-42", back.byArticle["sc-42"]?.articleNumber)
            assertEquals(setOf(4), back.addonCategoryIds)
            assertEquals(5, back.groupByProductId[77])
            assertEquals(5, back.groupByUuid["u-77"])
        } finally {
            file.delete()
        }
    }

    @Test
    fun `cached payload still hides the addon when merged offline`() {
        val file = File.createTempFile("catalog-cache", ".json")
        try {
            CatalogCache.saveTo(
                file,
                CatalogApi.Payload(
                    byUuid = mapOf("u-42" to enrich(hide = true, categoryId = 4)),
                    upsellDefault = emptyList(),
                    addonCategoryIds = setOf(4),
                ),
            )
            val cached = CatalogCache.loadFrom(file)!!
            val terminal = CatalogProduct(
                uuid = "u-42",
                name = "Карамель",
                priceRub = BigDecimal("40"),
                allowToSell = true,
                recipe = null,
                description = null,
                article = null,
            )
            val merged = CatalogApi.merge(listOf(terminal), cached).single()
            assertTrue("офлайн-кэш не показывает добавку обычной плиткой", merged.hideFromMenu)
            assertFalse(DrinkVariants.build(listOf(merged)).tiles.any { it.uuid == "u-42" })
        } finally {
            file.delete()
        }
    }

    @Test
    fun `cache keeps server catalog flag and sellable rows`() {
        val file = File.createTempFile("catalog-cache-server", ".json")
        try {
            val blocked = enrich(hide = false, categoryId = null).copy(
                productId = 43, name = "Раф", sellable = false, notReadyReason = "no_store_link",
            )
            val payload = CatalogApi.Payload(
                byUuid = mapOf("u-42" to enrich(hide = false, categoryId = null), "u-43" to blocked),
                upsellDefault = emptyList(),
                features = CatalogApi.Features(serverCatalog = true),
            )
            CatalogCache.saveTo(file, payload)
            val back = CatalogCache.loadFrom(file)
            assertEquals(true, back?.features?.serverCatalog)
            assertEquals(true, back?.byUuid?.get("u-42")?.sellable)
            assertEquals(false, back?.byUuid?.get("u-43")?.sellable)
            assertEquals("no_store_link", back?.byUuid?.get("u-43")?.notReadyReason)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `old cache without features keeps legacy mode`() {
        val file = File.createTempFile("catalog-cache-legacy", ".json")
        try {
            CatalogCache.saveTo(file, CatalogApi.Payload(byUuid = mapOf("u-42" to enrich(false, null)), upsellDefault = emptyList()))
            val back = CatalogCache.loadFrom(file)
            assertEquals(false, back?.features?.serverCatalog)
            assertEquals(true, back?.byUuid?.get("u-42")?.sellable)
        } finally {
            file.delete()
        }
    }
}
