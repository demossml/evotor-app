package ru.sixthcup.evotor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Отрисовка секций листа. Баг 155: `UI opts: milk=2 syrup=7 extra=10`, а на экране был только
 * «Объём» — значит условия секций и то, что реально видит кассир, расходились.
 * Теперь `OptionPicker.render()` спрашивает те же [SheetSections.headers], что и тесты.
 */
class SheetSectionsTest {

    @Test
    fun `prod-like options give volume, milk, syrup and additions`() {
        val headers = SheetSections.headers(
            variantCount = 3, milkCount = 2, syrupCount = 7, sugarCount = 0, extraCount = 10,
        )
        assertEquals(listOf("Объём", "Молоко", "Сироп", "Добавки"), headers)
    }

    @Test
    fun `empty options render the volume block only`() {
        assertEquals(listOf("Объём"), SheetSections.headers(3, 0, 0, 0, 0))
    }

    @Test
    fun `milk alone is enough for a section`() {
        assertEquals(listOf("Объём", "Молоко"), SheetSections.headers(2, 2, 0, 0, 0))
    }

    @Test
    fun `single variant without options shows nothing`() {
        assertEquals(emptyList<String>(), SheetSections.headers(1, 0, 0, 0, 0))
    }

    @Test
    fun `sugar gets its own section`() {
        assertEquals(listOf("Объём", "Сахар"), SheetSections.headers(3, 0, 0, 3, 0))
    }

    @Test
    fun `choice section sits between milk and syrup and appears alone`() {
        assertEquals(listOf("Объём", "Молоко", "Выбор", "Сироп"), SheetSections.headers(2, 2, 1, 0, 0, choiceCount = 1))
        assertEquals(listOf("Выбор"), SheetSections.headers(1, 0, 0, 0, 0, choiceCount = 1))
    }

    @Test
    fun `without choice the sections are unchanged`() {
        assertEquals(listOf("Объём", "Молоко"), SheetSections.headers(2, 2, 0, 0, 0, choiceCount = 0))
    }
}
