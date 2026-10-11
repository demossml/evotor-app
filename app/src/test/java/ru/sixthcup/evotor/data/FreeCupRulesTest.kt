package ru.sixthcup.evotor.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeCupRulesTest {
    private fun e(name: String, group: String? = null, desc: String? = null) = FreeCupRules.isEligible(name, group, desc)

    @Test fun drinksAreEligible() {
        listOf("Американо", "Капучино 300", "Латте", "Раф ванильный", "Флэт уайт", "Айс латте", "Чай чёрный", "Горячий шоколад", "Эспрессо")
            .forEach { assertTrue(it, e(it)) }
    }

    @Test fun foodIsNot() {
        listOf("Круассан", "Чизкейк", "Торт Наполеон", "Сэндвич с курицей", "Кофе зерновой 250 г", "Чайник стеклянный")
            .forEach { assertFalse(it, e(it)) }
    }

    @Test fun markersWin() {
        assertTrue(e("Бамбл особый", desc = "рецепт... #cup"))
        assertFalse(e("Американо", desc = "#nocup"))
    }

    @Test fun groupFallbackAndUnknown() {
        assertTrue(e("Фирменный напиток дня", group = "Напитки"))
        assertFalse(e("Фирменный десерт дня", group = "Выпечка"))
        assertFalse(e("Загадка"))
    }
}
