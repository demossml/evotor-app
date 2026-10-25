package ru.sixthcup.evotor.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Сквозная сверка на фикстуре backend (та же копия лежит в sixth-cup):
 * ответ GET /api/devices/catalog → единый парсер APK → правила добавок → валидатор чека.
 */
class AddonsCatalogFixtureTest {

    private val root: JSONObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("addons-catalog-fixture.json")
            ?: error("fixture addons-catalog-fixture.json is missing")
        JSONObject(stream.bufferedReader().use { it.readText() })
    }

    private fun products(): List<JSONObject> {
        val a = root.getJSONArray("products")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun drink(): JSONObject = products().first { it.getJSONArray("toppings").length() > 0 }

    private fun toppings(): List<Topping> = ToppingJson.parse(drink().getJSONArray("toppings"))

    private fun keyOf(t: Topping) = "t:${t.id}"

    private fun rules(all: List<Topping>, groupKey: String) =
        all.filter { it.groupKey == groupKey }.map { ToppingRules.ruleOf(it) }

    private fun groups(all: List<Topping>) = listOf(
        "Молоко" to rules(all, "milk"),
        "Сироп" to rules(all, "syrup"),
        "Добавки" to rules(all, "extra"),
    )

    private fun catalogProducts(all: List<Topping>): List<CatalogProduct> {
        val d = drink()
        val drinkProduct = CatalogProduct(
            uuid = d.getString("evotorUuid"),
            name = d.getString("name"),
            priceRub = BigDecimal(d.get("priceRub").toString()),
            allowToSell = true,
            recipe = null,
            description = null,
            article = null,
            toppings = all,
        )
        val addons = toppings().filter { !it.zeroOption }.mapNotNull { t ->
            t.evotorUuid?.let { uuid ->
                CatalogProduct(
                    uuid = uuid,
                    name = t.name,
                    priceRub = t.priceRub,
                    allowToSell = true,
                    recipe = null,
                    description = null,
                    article = null,
                    hideFromMenu = true,
                )
            }
        }
        return listOf(drinkProduct) + addons
    }

    private fun cartLines(all: List<Topping>): List<CartLine> {
        val drinkLine = CartLine(
            productUuid = drink().getString("evotorUuid"),
            name = drink().getString("name"),
            priceRub = BigDecimal(drink().get("priceRub").toString()),
        )
        val paid = all.filter { !it.zeroOption }
        return listOf(drinkLine) + paid.map { t ->
            CartLine(productUuid = t.evotorUuid!!, name = t.name, priceRub = t.priceRub, optionId = t.id)
        }
    }

    @Test
    fun `fixture keeps every addon contract field`() {
        val all = toppings()
        assertEquals(6, all.size)
        assertEquals(listOf("syrup", "syrup", "syrup", "milk", "milk", "extra"), all.map { it.groupKey })
        assertEquals(listOf("Без сиропа"), all.filter { it.groupKey == "syrup" && it.zeroOption }.map { it.name })

        val milkZero = all.first { it.groupKey == "milk" && it.zeroOption }
        assertEquals("Обычное", milkZero.name)
        assertTrue(milkZero.required)
        assertTrue(milkZero.isDefault)
        assertEquals(1, milkZero.maxCount)
        assertNull(milkZero.evotorUuid)
        assertTrue(milkZero.available)

        val milkPaid = all.first { it.groupKey == "milk" && !it.zeroOption }
        assertEquals("uuid-oat", milkPaid.evotorUuid)
        assertEquals(0, BigDecimal("60").compareTo(milkPaid.priceRub))
        assertEquals(false, milkPaid.required)

        val extra = all.first { it.groupKey == "extra" }
        assertEquals(2, extra.maxCount)
        assertEquals("uuid-cinnamon", extra.evotorUuid)
        assertTrue(all.filter { !it.zeroOption }.all { it.available && !it.evotorUuid.isNullOrBlank() })
    }

    @Test
    fun `single parser round-trips all fields`() {
        val all = toppings()
        assertEquals(all, ToppingJson.parse(ToppingJson.toJson(all)))
        // каталог отдаёт число, кэш пишет строку — результат обязан совпадать
        val asNumber = JSONObject("""{"id":1,"name":"a","priceRub":50.5,"groupKey":"extra"}""")
        val asString = JSONObject("""{"id":1,"name":"a","priceRub":"50.5","groupKey":"extra"}""")
        assertEquals(ToppingJson.parseOne(asNumber), ToppingJson.parseOne(asString))
    }

    @Test
    fun `required milk group blocks only until any sellable option is chosen`() {
        val all = toppings()
        val g = groups(all)
        val blocked = ToppingRules.blockers(g, emptySet())
        assertEquals(1, blocked.size)
        assertTrue(blocked.first().contains("Молоко"))
        assertTrue(blocked.first().contains("выберите вариант"))

        val milkPaid = all.first { it.groupKey == "milk" && !it.zeroOption }
        assertTrue("платная опция не помечена required, но закрывает группу", ToppingRules.blockers(g, setOf(keyOf(milkPaid))).isEmpty())
        val milkZero = all.first { it.groupKey == "milk" && it.zeroOption }
        assertTrue(ToppingRules.blockers(g, setOf(keyOf(milkZero))).isEmpty())
    }

    @Test
    fun `cart built from fixture passes pre-receipt validation`() {
        val all = toppings()
        assertTrue(ReceiptValidator.validate(cartLines(all), catalogProducts(all)).isEmpty())
    }

    @Test
    fun `paid option without uuid and removed option are caught`() {
        val all = toppings()
        val milkPaid = all.first { it.groupKey == "milk" && !it.zeroOption }

        val broken = all.map { if (it.id == milkPaid.id) it.copy(evotorUuid = null) else it }
        val uuidIssue = ReceiptValidator.validate(cartLines(all), catalogProducts(broken)).first()
        assertEquals("option_without_uuid", uuidIssue.reason)

        val removed = all.filter { it.id != milkPaid.id }
        val removedIssue = ReceiptValidator.validate(cartLines(all), catalogProducts(removed)).first()
        assertEquals("option_removed", removedIssue.reason)
    }

    @Test
    fun `fixture carries stable identity for addons and variant groups`() {
        val payload = ru.sixthcup.evotor.net.CatalogApi.parsePayload(root.toString())
        assertTrue("addonCategoryIds должен присутствовать", payload.addonCategoryIds.isNotEmpty())

        val all = products()
        val addons = all.filter { it.optBoolean("isModifier", false) }
        assertTrue("в фикстуре должны быть добавки", addons.isNotEmpty())
        addons.forEach { p ->
            assertTrue("добавка скрыта из меню", p.optBoolean("hideFromMenu", false))
            assertTrue("у добавки есть стабильный артикул", p.optString("articleNumber").startsWith("sc-"))
            assertTrue("артикул сопоставляется", payload.byArticle.containsKey(p.getString("articleNumber")))
        }

        val withGroup = all.filter { it.has("groupId") && !it.isNull("groupId") }
        assertTrue("варианты должны нести groupId", withGroup.isNotEmpty())
        withGroup.forEach { p ->
            val gid = p.getInt("groupId")
            assertTrue("groupId попадает в общий индекс", payload.groupByProductId.containsValue(gid))
        }
    }
}
