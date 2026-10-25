package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ToppingRulesTest {

    private fun topping(
        id: Int,
        price: String,
        uuid: String? = "uuid-$id",
        required: Boolean = false,
        isDefault: Boolean = false,
        zero: Boolean = false,
        available: Boolean = true,
        maxCount: Int = 1,
        group: String = "extra",
    ) = Topping(
        id = id,
        name = "opt-$id",
        priceRub = BigDecimal(price),
        groupKey = group,
        maxCount = maxCount,
        evotorUuid = uuid,
        required = required,
        isDefault = isDefault,
        zeroOption = zero,
        available = available,
    )

    @Test
    fun `paid topping without evotor uuid is not sellable`() {
        val rule = ToppingRules.ruleOf(topping(1, "50", uuid = null))
        assertTrue(rule.paid)
        assertFalse(rule.sellable)
    }

    @Test
    fun `zero option is selectable and never paid`() {
        val rule = ToppingRules.ruleOf(topping(1, "0", uuid = null, zero = true))
        assertFalse(rule.paid)
        assertTrue(rule.sellable)
    }

    @Test
    fun `unavailable topping is not selectable`() {
        assertFalse(ToppingRules.ruleOf(topping(1, "50", available = false)).sellable)
    }

    @Test
    fun `default is preselected only when flagged`() {
        val plain = listOf(topping(1, "50"), topping(2, "60"))
        assertEquals(null, ToppingRules.defaultOf(plain.map { ToppingRules.ruleOf(it) }))

        val flagged = listOf(topping(1, "50"), topping(2, "60", isDefault = true))
        assertEquals("t:2", ToppingRules.defaultOf(flagged.map { ToppingRules.ruleOf(it) })?.key)
    }

    @Test
    fun `required group falls back to zero option before paid option`() {
        val list = listOf(
            topping(1, "50", required = true),
            topping(2, "0", uuid = null, zero = true, required = true),
        )
        assertEquals("t:2", ToppingRules.defaultOf(list.map { ToppingRules.ruleOf(it) })?.key)
    }

    @Test
    fun `required paid group is not preselected without a default`() {
        val list = listOf(topping(1, "50", required = true), topping(2, "50", required = true))
        assertEquals(null, ToppingRules.defaultOf(list.map { ToppingRules.ruleOf(it) }))
    }

    @Test
    fun `required group is a group flag - any sellable option closes it`() {
        // Смешанная группа: обязательна только опция 2, гость выбирает опцию 1.
        val rules = listOf(
            ToppingRules.ruleOf(topping(1, "50", required = false)),
            ToppingRules.ruleOf(topping(2, "60", required = true)),
        )
        assertTrue("выбор необязательной опции закрывает обязательную группу", ToppingRules.blockers(listOf("Молоко" to rules), setOf("t:1")).isEmpty())
        val blocked = ToppingRules.blockers(listOf("Молоко" to rules), emptySet())
        assertEquals(1, blocked.size)
        assertTrue(blocked.first().contains("выберите вариант"))
        assertTrue(ToppingRules.requiredSatisfied(rules, setOf("t:1")))
        assertFalse(ToppingRules.requiredSatisfied(rules, emptySet()))
    }

    @Test
    fun `required group is satisfied by a zero option`() {
        val rules = listOf(
            ToppingRules.ruleOf(topping(1, "50", required = true)),
            ToppingRules.ruleOf(topping(2, "0", uuid = null, zero = true, required = true)),
        )
        assertTrue(ToppingRules.requiredSatisfied(rules, setOf("t:2")))
        assertFalse(ToppingRules.requiredSatisfied(rules, emptySet()))
    }

    @Test
    fun `groupLimit caps distinct options in a group - documented maxCount interpretation`() {
        val rules = listOf(
            ToppingRules.ruleOf(topping(1, "30", maxCount = 2)),
            ToppingRules.ruleOf(topping(2, "30", maxCount = 2)),
            ToppingRules.ruleOf(topping(3, "30", maxCount = 2)),
        )
        assertEquals(2, ToppingRules.groupLimit(rules))
        assertTrue(ToppingRules.canSelect(rules, emptySet(), "t:1"))
        assertTrue(ToppingRules.canSelect(rules, setOf("t:1"), "t:2"))
        assertFalse(ToppingRules.canSelect(rules, setOf("t:1", "t:2"), "t:3"))
        assertFalse(ToppingRules.canSelect(rules, setOf("t:1"), "t:1"))
    }

    @Test
    fun `blockers report missing required choice and missing evotor goods`() {
        val required = listOf(ToppingRules.ruleOf(topping(1, "50", required = true)))
        assertEquals(1, ToppingRules.blockers(listOf("Молоко" to required), emptySet()).size)
        assertTrue(ToppingRules.blockers(listOf("Молоко" to required), setOf("t:1")).isEmpty())

        val noGoods = listOf(ToppingRules.ruleOf(topping(1, "50", uuid = null, required = true)))
        val message = ToppingRules.blockers(listOf("Молоко" to noGoods), emptySet()).first()
        assertTrue(message.contains("нет доступных опций"))
    }
}
