package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUtilTest {

    /** Имитация посимвольного ввода в EditText: каждый шаг проходит через маску. */
    private fun typeChars(chars: String): String {
        var display = ""
        chars.forEach { ch ->
            display = PhoneUtil.formatDisplay(PhoneUtil.nationalDigits(display + ch))
        }
        return display
    }

    @Test
    fun `normalizes ten digits and leading eight`() {
        assertEquals("79001234567", PhoneUtil.normalize("900 123-45-67"))
        assertEquals("79001234567", PhoneUtil.normalize("8 (900) 123-45-67"))
        assertEquals("79001234567", PhoneUtil.normalize("+7 900 123 45 67"))
    }

    @Test
    fun `validates russian mobile length`() {
        assertTrue(PhoneUtil.isValid("+7 (900) 123-45-67"))
        assertTrue(PhoneUtil.isValid("9001234567"))
        assertFalse(PhoneUtil.isValid("12345"))
        assertFalse(PhoneUtil.isValid(""))
    }

    @Test
    fun `formats a mask`() {
        assertEquals("+7 (900) 123-45-67", PhoneUtil.format("79001234567"))
        assertEquals("+7 (900", PhoneUtil.format("7900"))
    }

    @Test
    fun `national digits strip country prefix from raw and display`() {
        assertEquals("", PhoneUtil.nationalDigits("8"))
        assertEquals("", PhoneUtil.nationalDigits("+7"))
        assertEquals("999", PhoneUtil.nationalDigits("8999"))
        assertEquals("999", PhoneUtil.nationalDigits("+7 (999"))
        assertEquals("9991234567", PhoneUtil.nationalDigits("89991234567"))
        assertEquals("9991234567", PhoneUtil.nationalDigits("79991234567"))
        assertEquals("9991234567", PhoneUtil.nationalDigits("+7 (999) 123-45-67"))
        assertEquals("9991234567", PhoneUtil.nationalDigits("9991234567"))
    }

    @Test
    fun `typing eight then digits never adds an extra seven`() {
        assertEquals("+7 ", typeChars("8"))
        assertEquals("+7 (999", typeChars("8999"))
        assertEquals("+7 (999) 123", typeChars("8999123"))
        assertEquals("+7 (999) 123-45-67", typeChars("89991234567"))
    }

    @Test
    fun `backspace removes one national digit without sevens`() {
        val full = typeChars("89991234567")
        val afterDelete = full.dropLast(1)
        val national = PhoneUtil.nationalDigits(afterDelete)
        assertEquals("999123456", national)
        assertEquals("+7 (999) 123-45-6", PhoneUtil.formatDisplay(national))
    }

    @Test
    fun `extra digit after full number is ignored`() {
        val full = typeChars("89991234567")
        val national = PhoneUtil.nationalDigits(full + "8")
        assertEquals("9991234567", national)
        assertEquals(full, PhoneUtil.formatDisplay(national))
    }

    @Test
    fun `display formatting is idempotent`() {
        val national = "9991234567"
        val display = PhoneUtil.formatDisplay(national)
        assertEquals(display, PhoneUtil.formatDisplay(PhoneUtil.nationalDigits(display)))
        assertEquals(national, PhoneUtil.nationalDigits(display))
    }

    @Test
    fun `api digits use seven plus ten national digits`() {
        assertEquals("79991234567", PhoneUtil.toApiDigits("9991234567"))
        assertEquals("999", PhoneUtil.toApiDigits("999"))
        assertTrue(PhoneUtil.isComplete("9991234567"))
        assertFalse(PhoneUtil.isComplete("999123456"))
    }
}
