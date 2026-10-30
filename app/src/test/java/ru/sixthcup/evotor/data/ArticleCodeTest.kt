package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArticleCodeTest {
    @Test
    fun `canon sc article wins over numeric evotor code`() {
        assertEquals("sc-5", ArticleCode.pick(listOf("00042", " sc-5 ")))
        assertEquals("SC-G-2", ArticleCode.pick(listOf(null, "SC-G-2")))
    }

    @Test
    fun `legacy first non-empty value when no canon`() {
        assertEquals("00042", ArticleCode.pick(listOf("", "00042", "abc")))
        assertNull(ArticleCode.pick(listOf(null, " ")))
    }
}
