package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Диалог «Повтор не завершён» не должен появляться поверх другой карточки и не должен
 * открываться дважды; текст уведомления считает остаток.
 */
class RepeatInterruptTest {

    @Test
    fun `dialog is shown only when nothing else is open`() {
        assertTrue(RepeatInterrupt.shouldShow(pending = 2, sheetOpen = false, dialogShowing = false))
    }

    @Test
    fun `another card on screen suppresses the dialog`() {
        assertFalse(
            "не показываем поверх другой карточки",
            RepeatInterrupt.shouldShow(pending = 2, sheetOpen = true, dialogShowing = false),
        )
    }

    @Test
    fun `already visible dialog is not opened twice`() {
        assertFalse(RepeatInterrupt.shouldShow(pending = 2, sheetOpen = false, dialogShowing = true))
    }

    @Test
    fun `no interruption means no dialog`() {
        assertFalse(RepeatInterrupt.shouldShow(pending = null, sheetOpen = false, dialogShowing = false))
    }

    @Test
    fun `notice reports the remaining positions`() {
        assertEquals("Повтор не завершён: осталось 3 поз.", RepeatInterrupt.notice(3, "Капучино"))
        assertEquals("Повтор не завершён: Капучино не добавлен", RepeatInterrupt.notice(0, "Капучино"))
    }
}
