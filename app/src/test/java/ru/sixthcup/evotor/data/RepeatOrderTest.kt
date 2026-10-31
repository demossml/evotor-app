package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * «Повторить» на пустом чеке: прошлый заказ не копируется, а проходит через те же проверки,
 * что обычное добавление. Платное молоко и добавки (строки с `optionId`) не восстанавливаются.
 */
class RepeatOrderTest {

    private fun d(s: String) = BigDecimal(s)

    private fun product(uuid: String, name: String = "Капучино", article: String? = null) = CatalogProduct(
        uuid = uuid, name = name, priceRub = d("250"), allowToSell = true,
        recipe = null, description = null, article = article,
    )

    private fun drink(uuid: String = "VOL-250") = CartLine(
        productUuid = uuid, name = "Капучино 250 мл", priceRub = d("250"), quantity = BigDecimal.ONE,
        cartKey = "VOL-250|m:c:m:2",
    )

    private fun paidMilkLine() = CartLine(
        productUuid = "MILK-COCONUT", name = "Кокосовое молоко (Капучино 250 мл)",
        priceRub = d("60"), optionId = 2,
    )

    // 1. Обязательное молоко: товар не восстанавливается, а снова идёт в окно выбора.
    @Test
    fun `drink with mandatory milk needs the choice again`() {
        val plan = RepeatOrder.plan(
            lastOrder = listOf(drink(), paidMilkLine()),
            catalog = listOf(product("VOL-250")),
            noConfigConfirmed = { false },
        )
        assertTrue("ничего не восстановлено молча", plan.restore.isEmpty())
        assertEquals(listOf("VOL-250"), plan.needsChoice.map { it.uuid })
        assertEquals("платное молоко выбирается заново", 1, plan.skipped.size)
        assertEquals("MILK-COCONUT", plan.skipped.first().productUuid)
    }

    // 2. Конфигурация недоступна (сервер/404/таймаут): молчаливое добавление запрещено.
    @Test
    fun `unavailable configuration never restores silently`() {
        val plan = RepeatOrder.plan(
            lastOrder = listOf(drink()),
            catalog = listOf(product("VOL-250")),
            noConfigConfirmed = { false },
        )
        assertTrue(plan.restore.isEmpty())
        assertEquals(1, plan.needsChoice.size)
    }

    // 3. Товар удалён из каталога — не восстанавливаем как обычную позицию.
    @Test
    fun `removed product is skipped`() {
        val plan = RepeatOrder.plan(
            lastOrder = listOf(drink("GONE")),
            catalog = emptyList(),
            noConfigConfirmed = { true },
        )
        assertTrue(plan.restore.isEmpty())
        assertTrue(plan.needsChoice.isEmpty())
        assertEquals(1, plan.skipped.size)
        assertTrue(plan.skipped.first().reason.contains("каталог"))
        assertTrue(plan.isEmpty)
    }

    // 4. Подтверждённое «схем нет» — обычное добавление разрешено (как при тапе по плитке).
    @Test
    fun `confirmed no configuration restores the line`() {
        val line = drink()
        val plan = RepeatOrder.plan(
            lastOrder = listOf(line),
            catalog = listOf(product("VOL-250")),
            noConfigConfirmed = { true },
        )
        assertEquals(listOf(line), plan.restore)
        assertTrue(plan.needsChoice.isEmpty())
        assertTrue(plan.skipped.isEmpty())
    }

    // 5. Платные опции не восстанавливаются даже при подтверждённом «схем нет».
    @Test
    fun `option lines are never restored even for a plain product`() {
        val plan = RepeatOrder.plan(
            lastOrder = listOf(drink(), paidMilkLine()),
            catalog = listOf(product("VOL-250"), product("MILK-COCONUT")),
            noConfigConfirmed = { true },
        )
        assertEquals(listOf("VOL-250"), plan.restore.map { it.productUuid })
        assertTrue(plan.skipped.any { it.productUuid == "MILK-COCONUT" })
    }

    // 6. Комбо-группа не восстанавливается по частям.
    @Test
    fun `combo group is not restored line by line`() {
        val combo = drink().copy(comboGroupId = "combo-1", comboId = "c1")
        val plan = RepeatOrder.plan(
            lastOrder = listOf(combo, paidMilkLine()),
            catalog = listOf(product("VOL-250"), product("MILK-COCONUT")),
            noConfigConfirmed = { true },
        )
        assertTrue(plan.restore.isEmpty())
        assertTrue(plan.isEmpty)
        assertTrue(plan.skipped.any { it.reason.contains("комбо") })
    }

    // 7. Синтетическая позиция без товара Эвотор не восстанавливается.
    @Test
    fun `synthetic line is skipped`() {
        val synthetic = CartLine(productUuid = "mod:milk", name = "Молоко", priceRub = d("0"))
        val plan = RepeatOrder.plan(
            lastOrder = listOf(synthetic),
            catalog = listOf(product("mod:milk")),
            noConfigConfirmed = { true },
        )
        assertTrue(plan.restore.isEmpty())
        assertTrue(plan.skipped.any { it.reason.contains("синтетическая") })
    }

    // 8. Один и тот же товар из прошлого чека не размножается в очереди.
    @Test
    fun `same product is queued once`() {
        val plan = RepeatOrder.plan(
            lastOrder = listOf(drink(), drink()),
            catalog = listOf(product("VOL-250")),
            noConfigConfirmed = { false },
        )
        assertEquals(1, plan.needsChoice.size)
        assertFalse(plan.isEmpty)
    }
}
