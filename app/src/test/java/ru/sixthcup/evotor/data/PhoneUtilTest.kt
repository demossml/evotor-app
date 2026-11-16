package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUtilTest {

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
}
