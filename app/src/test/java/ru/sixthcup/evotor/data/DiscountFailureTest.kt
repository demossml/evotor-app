package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** P2-5: сбой скидки не «глотается» — у кассира есть понятный текст с причиной. */
class DiscountFailureTest {

    @Test
    fun `reason is shown to the cashier`() {
        assertEquals(
            "Не удалось применить скидку (IllegalStateException). Позовите администратора",
            DiscountFailure.cashierText("IllegalStateException"),
        )
    }

    @Test
    fun `empty reason still gives an actionable message`() {
        assertEquals(
            "Не удалось применить скидку. Позовите администратора",
            DiscountFailure.cashierText(null),
        )
        assertEquals(
            "Не удалось применить скидку. Позовите администратора",
            DiscountFailure.cashierText("  "),
        )
    }
}
