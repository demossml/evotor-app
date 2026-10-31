package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.CatalogApi
import java.math.BigDecimal

/**
 * Server-driven витрина: плитки и состав листа из серверного каталога,
 * без склейки по имени и без донора добавок (CHANGES-FROM-PLAN F2–F4).
 */
class ServerCatalogTest {

    private fun terminal(uuid: String, name: String, price: String = "250", article: String? = null) =
        CatalogProduct(
            uuid = uuid, name = name, priceRub = BigDecimal(price), allowToSell = true,
            recipe = null, description = null, article = article,
        )

    private fun enrich(
        id: Int,
        name: String,
        toppings: List<Topping> = emptyList(),
        sellable: Boolean = true,
        hide: Boolean = false,
        reason: String? = null,
    ) = CatalogApi.Enrich(
        productId = id, name = name, imageUrl = null, recipe = null, countsAsCup = null,
        freeEligible = null, toppings = toppings, upsell = emptyList(),
        hideFromMenu = hide, sellable = sellable, notReadyReason = reason,
    )

    private fun milk() = Topping(
        id = 1, name = "Овсяное", priceRub = BigDecimal("50"), groupKey = "milk",
        evotorUuid = "uuid-milk", required = false,
    )

    private fun payload(
        byUuid: Map<String, CatalogApi.Enrich> = emptyMap(),
        byNameExact: Map<String, CatalogApi.Enrich> = emptyMap(),
        byArticle: Map<String, CatalogApi.Enrich> = emptyMap(),
        serverCatalog: Boolean = true,
    ) = CatalogApi.Payload(
        byUuid = byUuid, upsellDefault = emptyList(),
        byNameExact = byNameExact, byArticle = byArticle,
        features = CatalogApi.Features(serverCatalog),
    )

    @Test
    fun `shelf is built from server products that exist on the terminal`() {
        val shelf = ServerCatalog.build(
            listOf(terminal("u-capp", "Капучино 250 мл"), terminal("u-latte", "Латте 250 мл")),
            payload(byUuid = mapOf("u-capp" to enrich(1, "Капучино 250 мл"))),
        )
        assertEquals(1, shelf.shown)
        assertEquals(listOf("Капучино 250 мл"), shelf.products.map { it.name })
        assertEquals(1, shelf.absentOnServer)
    }

    @Test
    fun `not sellable products never reach the shelf but are counted`() {
        val shelf = ServerCatalog.build(
            listOf(terminal("u-capp", "Капучино 250 мл"), terminal("u-new", "Раф")),
            payload(
                byUuid = mapOf(
                    "u-capp" to enrich(1, "Капучино 250 мл"),
                    "u-new" to enrich(2, "Раф", sellable = false, reason = "no_store_link"),
                ),
            ),
        )
        assertEquals(listOf("Капучино 250 мл"), shelf.products.map { it.name })
        assertEquals(1, shelf.notSellable)
    }

    @Test
    fun `product missing on the terminal is hidden and counted`() {
        val shelf = ServerCatalog.build(
            listOf(terminal("u-capp", "Капучино 250 мл")),
            payload(
                byUuid = mapOf(
                    "u-capp" to enrich(1, "Капучино 250 мл"),
                    "u-not-pushed" to enrich(2, "Флэт уайт"),
                ),
            ),
        )
        assertEquals(1, shelf.shown)
        assertEquals(1, shelf.missingOnTerminal)
    }

    @Test
    fun `hidden modifiers and groups are not tiles`() {
        val shelf = ServerCatalog.build(
            listOf(terminal("u-milk", "Молоко"), terminal("u-capp", "Капучино 250 мл")),
            payload(
                byUuid = mapOf(
                    "u-milk" to enrich(1, "Молоко", hide = true),
                    "u-capp" to enrich(2, "Капучино 250 мл"),
                ),
            ),
        )
        assertEquals(listOf("Капучино 250 мл"), shelf.products.map { it.name })
        assertEquals(1, shelf.hiddenByFlag)
    }

    @Test
    fun `sheet toppings come from the product itself, never from a sibling size`() {
        val payload = payload(
            byUuid = mapOf(
                "u-250" to enrich(1, "Капучино 250 мл", toppings = listOf(milk())),
                "u-350" to enrich(2, "Капучино 350 мл", toppings = emptyList()),
            ),
        )
        val shelf = ServerCatalog.build(
            listOf(terminal("u-250", "Капучино 250 мл"), terminal("u-350", "Капучино 350 мл")),
            payload,
        )
        val p250 = shelf.products.single { it.uuid == "u-250" }
        val p350 = shelf.products.single { it.uuid == "u-350" }
        assertEquals(1, p250.toppings.size)
        assertTrue("у 350 своей схемы нет — донора в server-режиме быть не должно", p350.toppings.isEmpty())
        assertFalse(p350.supportsMods)
    }

    @Test
    fun `identity by article works when uuid differs`() {
        val shelf = ServerCatalog.build(
            listOf(terminal("local-1", "Капучино 250 мл", article = "sc-4")),
            payload(byArticle = mapOf("sc-4" to enrich(9, "Капучино 250 мл", toppings = listOf(milk())))),
        )
        assertEquals(1, shelf.shown)
        assertEquals(1, shelf.products.single().toppings.size)
    }

    @Test
    fun `volume stripped name is not used as identity in server mode`() {
        // «капучино» — одно имя на три размера; склейка по нему запрещена.
        val shelf = ServerCatalog.build(
            listOf(terminal("local-1", "Капучино 250 мл")),
            CatalogApi.Payload(
                byUuid = emptyMap(), upsellDefault = emptyList(),
                byName = mapOf("капучино" to enrich(1, "Капучино 250 мл")),
                features = CatalogApi.Features(true),
            ),
        )
        assertEquals(0, shelf.shown)
        assertEquals(1, shelf.absentOnServer)
    }

    @Test
    fun `flags default to legacy behaviour when features are absent`() {
        val legacy = CatalogApi.Payload(byUuid = mapOf("u" to enrich(1, "x", sellable = false)), upsellDefault = emptyList())
        assertEquals(null, legacy.features)
        // Без поля sellable строка считается продаваемой — старый backend не ломает витрину.
        assertTrue(enrich(1, "x").sellable)
    }

    @Test
    fun `required milk blocks the receipt until the guest picks one`() {
        val requiredMilk = milk().copy(id = 2, name = "Молоко", priceRub = BigDecimal.ZERO, required = true)
        val shelf = ServerCatalog.build(
            listOf(terminal("u-capp", "Капучино 250 мл")),
            payload(byUuid = mapOf("u-capp" to enrich(1, "Капучино 250 мл", toppings = listOf(requiredMilk)))),
        )
        val product = shelf.products.single()
        val group = listOf("Молоко" to product.toppings.map { ToppingRules.ruleOf(it) })
        // Ничего не выбрано — блокер «выберите вариант» обязателен (ToppingRules).
        assertTrue(ToppingRules.blockers(group, emptySet()).isNotEmpty())
        // Выбран любой sellable вариант этой группы — блокеров нет.
        assertTrue(ToppingRules.blockers(group, setOf(ToppingRules.ruleOf(requiredMilk).key)).isEmpty())
    }
}
