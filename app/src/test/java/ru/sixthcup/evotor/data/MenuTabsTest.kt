package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.CatalogApi
import java.io.File
import java.math.BigDecimal

/**
 * Порядок вкладок групп и чип «Всё» приходят из админки (`menu` в каталоге).
 * Без `menu` (старый backend / старый кэш) — ровно прежнее поведение: алфавит и «Всё».
 */
class MenuTabsTest {

    private fun product(name: String, group: String?, category: String? = null) = CatalogProduct(
        uuid = "u-$name",
        name = name,
        priceRub = BigDecimal("100"),
        allowToSell = true,
        recipe = null,
        description = null,
        article = null,
        groupName = group,
        categoryName = category,
    )

    private val menu = CatalogApi.Menu(showAllTab = false, order = listOf("Чай", "Кофе", "Выпечка"))

    @Test
    fun `groups follow admin order instead of alphabet`() {
        val labels = listOf("Выпечка", "Десерты", "Кофе", "Чай")
        assertEquals(
            listOf("Чай", "Кофе", "Выпечка", "Десерты"),
            MenuTabs.order(labels, emptyList(), menu),
        )
    }

    @Test
    fun `groups outside the admin list go last and stay alphabetical`() {
        val labels = listOf("Смузи", "Кофе", "Десерты", "Чай")
        assertEquals(
            listOf("Кофе", "Чай", "Десерты", "Смузи"),
            MenuTabs.order(labels, emptyList(), CatalogApi.Menu(order = listOf("Кофе", "Чай"))),
        )
    }

    @Test
    fun `without menu the order is the same as before - alphabetical`() {
        val labels = listOf("Выпечка", "Кофе", "Чай")
        assertEquals(listOf("Выпечка", "Кофе", "Чай"), MenuTabs.order(labels, emptyList(), null))
        // menu без order (backend отдал только showAllTab) — тоже прежнее поведение.
        assertEquals(
            listOf("Выпечка", "Кофе", "Чай"),
            MenuTabs.order(labels, emptyList(), CatalogApi.Menu(showAllTab = false)),
        )
    }

    @Test
    fun `admin order matches despite case and spaces`() {
        val labels = listOf("кофе", "ЧАЙ")
        assertEquals(
            listOf("ЧАЙ", "кофе"),
            MenuTabs.order(labels, emptyList(), CatalogApi.Menu(order = listOf(" чай ", "Кофе"))),
        )
    }

    @Test
    fun `evotor folder with another name is ordered by backend category`() {
        val products = listOf(
            product("Капучино", group = "Напитки", category = "Кофе"),
            product("Чай зелёный", group = "Напитки 2", category = "Чай"),
        )
        assertEquals(
            listOf("Напитки 2", "Напитки"),
            MenuTabs.order(listOf("Напитки", "Напитки 2"), products, menu),
        )
    }

    @Test
    fun `all tab is shown unless admin disabled it`() {
        assertTrue(MenuTabs.showAll(null))
        assertTrue(MenuTabs.showAll(CatalogApi.Menu()))
        assertTrue(MenuTabs.showAll(CatalogApi.Menu(showAllTab = true, order = listOf("Чай"))))
        assertFalse(MenuTabs.showAll(CatalogApi.Menu(showAllTab = false)))
    }

    @Test
    fun `catalog payload parses menu block`() {
        val json = """
            {
              "menu": { "showAllTab": false, "order": ["Чай", "Кофе", "Чай", ""] },
              "categories": [{ "id": 2, "name": "Чай", "sortOrder": 1 }],
              "products": []
            }
        """.trimIndent()
        val payload = CatalogApi.parsePayload(json)
        assertEquals(CatalogApi.Menu(showAllTab = false, order = listOf("Чай", "Кофе")), payload.menu)
    }

    @Test
    fun `payload without menu keeps legacy behaviour`() {
        val payload = CatalogApi.parsePayload("""{ "products": [] }""")
        assertEquals(null, payload.menu)
        assertTrue(MenuTabs.showAll(payload.menu))
        assertEquals(listOf("Кофе", "Чай"), MenuTabs.order(listOf("Чай", "Кофе"), emptyList(), payload.menu))
    }

    @Test
    fun `menu survives offline cache round trip`() {
        val file = File.createTempFile("catalog-menu", ".json")
        try {
            val payload = CatalogApi.Payload(
                byUuid = mapOf(
                    "u-1" to CatalogApi.Enrich(
                        productId = 1,
                        name = "Капучино",
                        imageUrl = null,
                        recipe = null,
                        countsAsCup = null,
                        freeEligible = null,
                        toppings = emptyList(),
                        upsell = emptyList(),
                    ),
                ),
                upsellDefault = emptyList(),
                menu = CatalogApi.Menu(showAllTab = false, order = listOf("Чай", "Кофе")),
            )
            CatalogCache.saveTo(file, payload)
            val back = CatalogCache.loadFrom(file)!!
            assertEquals(CatalogApi.Menu(showAllTab = false, order = listOf("Чай", "Кофе")), back.menu)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `old cache without menu loads with legacy tabs`() {
        val file = File.createTempFile("catalog-nomenu", ".json")
        try {
            file.writeText(
                """
                {
                  "byUuid": { "u-1": { "productId": 1, "name": "Капучино" } },
                  "categoryNames": ["Кофе"],
                  "savedAt": 1
                }
                """.trimIndent(),
            )
            val back = CatalogCache.loadFrom(file)!!
            assertEquals(null, back.menu)
            assertTrue(MenuTabs.showAll(back.menu))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `hidden group loses its chip and does not appear at the end`() {
        val labels = listOf("Выпечка", "Десерты", "Кофе", "Чай")
        val menu = CatalogApi.Menu(order = listOf("Чай", "Кофе"), hidden = listOf("Выпечка"))
        val tiles = MenuTabs.order(labels, emptyList(), menu)
        assertFalse(tiles.contains("Выпечка"))
        // Скрытая группа не должна занимать место в хвосте «неизвестных по алфавиту».
        assertEquals(listOf("Чай", "Кофе", "Десерты"), tiles)
    }

    @Test
    fun `hidden matches group name with the same normalisation as order`() {
        val labels = listOf("Выпечка", "Чай")
        val menu = CatalogApi.Menu(order = listOf("Чай"), hidden = listOf("  выпечка ".trim(), "", "ВЫПЕЧКА"))
        assertEquals(listOf("Чай"), MenuTabs.order(labels, emptyList(), menu))
    }

    @Test
    fun `hidden group is found by backend category when the evotor folder is named differently`() {
        val products = listOf(
            product("Круассан", group = "Витрина", category = "Выпечка"),
            product("Капучино", group = "Напитки", category = "Кофе"),
        )
        val menu = CatalogApi.Menu(order = listOf("Кофе"), hidden = listOf("Выпечка"))
        assertEquals(listOf("Напитки"), MenuTabs.order(listOf("Витрина", "Напитки"), products, menu))
    }

    @Test
    fun `mixed folder is not hidden by one hidden category`() {
        // Папка Эвотора поверх двух категорий: спрятать половину товаров касса не может.
        val products = listOf(
            product("Круассан", group = "Витрина", category = "Выпечка"),
            product("Капучино", group = "Витрина", category = "Кофе"),
        )
        val menu = CatalogApi.Menu(hidden = listOf("Выпечка"))
        assertEquals(listOf("Витрина"), MenuTabs.order(listOf("Витрина"), products, menu))
    }

    @Test
    fun `empty or missing hidden list keeps current behaviour`() {
        val labels = listOf("Выпечка", "Кофе", "Чай")
        val expected = listOf("Чай", "Кофе", "Выпечка")
        assertEquals(expected, MenuTabs.order(labels, emptyList(), CatalogApi.Menu(order = listOf("Чай", "Кофе"))))
        assertEquals(
            expected,
            MenuTabs.order(labels, emptyList(), CatalogApi.Menu(order = listOf("Чай", "Кофе"), hidden = emptyList())),
        )
    }

    @Test
    fun `all groups hidden means no chips at all`() {
        val menu = CatalogApi.Menu(showAllTab = false, order = emptyList(), hidden = listOf("Выпечка", "Кофе"))
        assertEquals(emptyList<String>(), MenuTabs.order(listOf("Выпечка", "Кофе"), emptyList(), menu))
    }

    @Test
    fun `catalog payload parses hidden groups`() {
        val json = """
            {
              "menu": { "showAllTab": false, "order": ["Чай", "Кофе"], "hidden": ["Выпечка", "Выпечка", " "] },
              "products": []
            }
        """.trimIndent()
        val payload = CatalogApi.parsePayload(json)
        assertEquals(
            CatalogApi.Menu(showAllTab = false, order = listOf("Чай", "Кофе"), hidden = listOf("Выпечка")),
            payload.menu,
        )
    }

    @Test
    fun `old backend without hidden field parses to empty list`() {
        val payload = CatalogApi.parsePayload("""{ "menu": { "order": ["Чай"] }, "products": [] }""")
        assertEquals(emptyList<String>(), payload.menu?.hidden)
    }

    @Test
    fun `hidden groups survive offline cache round trip`() {
        val file = File.createTempFile("catalog-hidden", ".json")
        try {
            val payload = CatalogApi.Payload(
                byUuid = mapOf(
                    "u-1" to CatalogApi.Enrich(
                        productId = 1,
                        name = "Капучино",
                        imageUrl = null,
                        recipe = null,
                        countsAsCup = null,
                        freeEligible = null,
                        toppings = emptyList(),
                        upsell = emptyList(),
                    ),
                ),
                upsellDefault = emptyList(),
                menu = CatalogApi.Menu(showAllTab = false, order = listOf("Чай", "Кофе"), hidden = listOf("Выпечка")),
            )
            CatalogCache.saveTo(file, payload)
            val back = CatalogCache.loadFrom(file)!!
            assertEquals(listOf("Выпечка"), back.menu?.hidden)
            assertEquals(CatalogApi.Menu(showAllTab = false, order = listOf("Чай", "Кофе"), hidden = listOf("Выпечка")), back.menu)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `old cache without hidden keeps legacy tabs`() {
        val file = File.createTempFile("catalog-nohidden", ".json")
        try {
            file.writeText(
                """
                {
                  "byUuid": { "u-1": { "productId": 1, "name": "Капучино" } },
                  "menu": { "showAllTab": true, "order": ["Кофе"] },
                  "savedAt": 1
                }
                """.trimIndent(),
            )
            val back = CatalogCache.loadFrom(file)!!
            assertEquals(emptyList<String>(), back.menu?.hidden)
            // Порядок из кэша работает, но ничего не скрыто: «Чай» остаётся в конце.
            assertEquals(listOf("Кофе", "Чай"), MenuTabs.order(listOf("Кофе", "Чай"), emptyList(), back.menu))
        } finally {
            file.delete()
        }
    }
}
