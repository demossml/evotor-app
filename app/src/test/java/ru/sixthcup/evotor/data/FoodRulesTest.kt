package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodRulesTest {
    @Test fun markerWins() {
        assertTrue(FoodRules.isFood("Что-то", null, null, "вкусно #food"))
        assertFalse(FoodRules.isFood("Круассан", "Выпечка", null, "#nofood"))
    }

    @Test fun comboRefIsNotAFoodFlag() {
        assertFalse(FoodRules.isFood("Комбо", null, null, "#combo #drink=abc #food=def"))
    }

    @Test fun serverFlagBeatsGuessing() {
        assertTrue(FoodRules.isFood("Нечто", null, null, null, serverFlag = true))
        assertFalse(FoodRules.isFood("Круассан", null, null, null, serverFlag = false))
    }

    @Test fun groupOrCategory() {
        assertTrue(FoodRules.isFood("Эклер", "Десерты", null, null))
        assertTrue(FoodRules.isFood("Х", null, "Выпечка", null))
        assertFalse(FoodRules.isFood("Капучино", "Кофе", "Напитки", null))
    }

    @Test fun optionsAndDrinksAreNotFood() {
        assertFalse(FoodRules.isFood("Овсяное молоко", "Опции", null, "#opt=milk"))
        assertFalse(FoodRules.isFood("Латте", null, null, null))
    }

    @Test fun cartHasFood() {
        val drink = CartLine("d", "Капучино", java.math.BigDecimal.TEN)
        val cake = CartLine("f", "Чизкейк", java.math.BigDecimal.TEN)
        val comboLine = CartLine("d2", "Латте", java.math.BigDecimal.TEN, comboGroupId = "g1")
        assertFalse(FoodRules.cartHasFood(listOf(drink), setOf("f")))
        assertTrue(FoodRules.cartHasFood(listOf(drink, cake), setOf("f")))
        assertTrue(FoodRules.cartHasFood(listOf(comboLine), emptySet()))
        assertEquals(false, FoodRules.cartHasFood(emptyList(), setOf("f")))
    }
}
