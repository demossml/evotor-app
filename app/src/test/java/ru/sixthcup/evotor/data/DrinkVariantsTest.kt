package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class DrinkVariantsTest {

    private fun product(
        uuid: String,
        name: String,
        price: Int,
        variantGroup: String? = null,
        sizeMl: Int? = null,
        supportsMods: Boolean = true,
        toppings: List<Topping> = emptyList(),
    ) = CatalogProduct(
        uuid = uuid,
        name = name,
        priceRub = BigDecimal(price),
        allowToSell = true,
        recipe = null,
        description = null,
        article = null,
        variantGroup = variantGroup,
        sizeMl = sizeMl,
        supportsMods = supportsMods,
        toppings = toppings,
    )

    @Test
    fun `name alone never merges unrelated products`() {
        // Без подтверждённой группы объём в названии — не основание склеивать товары.
        val model = DrinkVariants.build(
            listOf(
                product("u250", "Капучино 250 мл", 220),
                product("u350", "Капучино 350 мл", 250),
                product("u450", "Капучино 450 мл", 300),
            ),
        )
        assertEquals(3, model.tiles.size)
    }

    @Test
    fun `sizes are grouped by confirmed group`() {
        val model = DrinkVariants.build(
            listOf(
                product("u250", "Капучино 250 мл", 220, variantGroup = "admin-group:5", sizeMl = 250),
                product("u350", "Капучино 350 мл", 250, variantGroup = "admin-group:5", sizeMl = 350),
                product("u450", "Капучино 450 мл", 300, variantGroup = "admin-group:5", sizeMl = 450),
            ),
        )
        assertEquals(1, model.tiles.size)
        val tile = model.tiles.first()
        assertEquals("Капучино", tile.name)
        assertEquals(listOf(250, 350, 450), model.variantsOf(tile).map { it.sizeMl })
        assertTrue(model.hasChoice(tile))
    }

    @Test
    fun `explicit group marker wins over name parsing`() {
        val model = DrinkVariants.build(
            listOf(
                product("a", "Латте 0,3", 260, variantGroup = "latte", sizeMl = 300),
                product("b", "Латте 0,4", 290, variantGroup = "latte", sizeMl = 400),
            ),
        )
        assertEquals(1, model.tiles.size)
        assertEquals(2, model.variantsOf(model.tiles.first()).size)
    }

    @Test
    fun `single product stays a single tile without size choice`() {
        val model = DrinkVariants.build(listOf(product("u1", "Капучино", 250)))
        assertEquals(1, model.tiles.size)
        assertEquals("Капучино", model.tiles.first().name)
        assertFalse(model.hasChoice(model.tiles.first()))
    }

    @Test
    fun `backend toppings alone open the item sheet`() {
        val topping = Topping(id = 1, name = "Ваниль", priceRub = BigDecimal(30), groupKey = "syrup", evotorUuid = "t1")
        val model = DrinkVariants.build(listOf(product("u1", "Капучино", 250, toppings = listOf(topping))))
        val tile = model.tiles.first()
        assertTrue(model.hasChoice(tile))
    }

    @Test
    fun `food does not open the item sheet`() {
        val model = DrinkVariants.build(listOf(product("f1", "Круассан", 180, supportsMods = false)))
        val tile = model.tiles.first()
        assertFalse(model.hasChoice(tile))
    }

    @Test
    fun `option products are not shown as tiles`() {
        val model = DrinkVariants.build(
            listOf(
                product("d", "Капучино", 250),
                product("m", "Молоко овсяное", 40, supportsMods = false).copy(optGroup = "milk"),
            ),
        )
        assertEquals(1, model.tiles.size)
        assertEquals(1, model.options.milk.size)
    }

    @Test
    fun `same drink name in different groups is not merged`() {
        val model = DrinkVariants.build(
            listOf(
                product("a", "Раф 200 мл", 200).copy(groupName = "Категория A"),
                product("b", "Раф 200 мл", 200).copy(groupName = "Категория B"),
            ),
        )
        assertEquals(2, model.tiles.size)
    }

    @Test
    fun `addon with the same name as a topping is not hidden without identity`() {
        val top = Topping(id = 1, name = "Карамель", priceRub = BigDecimal("30"), groupKey = "syrup", evotorUuid = "other-uuid")
        val drink = product("d", "Капучино", 250, toppings = listOf(top))
        val retail = product("retail", "Карамель", 30, supportsMods = false)
        val model = DrinkVariants.build(listOf(drink, retail))
        assertEquals("совпадение имени не скрывает обычный товар", 2, model.tiles.size)
    }

    @Test
    fun `addon sku is hidden by its evotor uuid`() {
        val top = Topping(id = 1, name = "Карамель", priceRub = BigDecimal("30"), groupKey = "syrup", evotorUuid = "addon-uuid")
        val drink = product("d", "Капучино", 250, toppings = listOf(top))
        val addon = product("addon-uuid", "Карамель", 30, supportsMods = false)
        val model = DrinkVariants.build(listOf(drink, addon))
        assertEquals(1, model.tiles.size)
    }

    @Test
    fun `lone size-labelled product is not lost from the menu`() {
        val model = DrinkVariants.build(listOf(product("u1", "Капучино 200 мл", 200)))
        assertEquals(1, model.tiles.size)
        assertEquals("Капучино 200 мл", model.tiles.first().name)
    }
}
