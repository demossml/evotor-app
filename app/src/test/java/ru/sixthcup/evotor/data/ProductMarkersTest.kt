package ru.sixthcup.evotor.data

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductMarkersTest {
    @Test fun focusTag() {
        assertTrue(ProductMarkers.isFocus("Хит #focus"))
        assertTrue(ProductMarkers.isFocus("#FOCUS"))
        assertFalse(ProductMarkers.isFocus("обычный"))
        assertFalse(ProductMarkers.isFocus(null))
    }

    @Test fun comboByNameOrTag() {
        assertTrue(ProductMarkers.isCombo("Капучино + круассан", null))
        assertTrue(ProductMarkers.isCombo("Набор", "#combo"))
        // «+» без пробелов (например «Латте 0,3+») комбо не считается
        assertFalse(ProductMarkers.isCombo("Латте 0,3+", null))
        assertFalse(ProductMarkers.isCombo("Американо", ""))
    }

    @Test fun oldPrice() {
        assertEquals(BigDecimal("250"), ProductMarkers.oldPriceRub("#old=250"))
        assertEquals(BigDecimal("199.50"), ProductMarkers.oldPriceRub("акция #old: 199,50 ещё"))
        assertNull(ProductMarkers.oldPriceRub("#old=abc"))
        assertNull(ProductMarkers.oldPriceRub(null))
    }

    @Test fun cleanDescriptionStripsMarkers() {
        assertEquals("Эспрессо, молоко", ProductMarkers.cleanDescription("Эспрессо, молоко #focus #old=300 #cup"))
        assertNull(ProductMarkers.cleanDescription("#focus #combo"))
        assertNull(ProductMarkers.cleanDescription(null))
    }
}
