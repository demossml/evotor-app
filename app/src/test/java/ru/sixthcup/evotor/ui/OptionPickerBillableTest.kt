package ru.sixthcup.evotor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** D-08: опция с sendToReceipt=false не должна менять сумму и попадать в чек. */
class OptionPickerBillableTest {

    private fun opt(name: String, price: String, sendToReceipt: Boolean) = OptionPicker.Opt(
        key = "t:$name",
        label = name,
        price = BigDecimal(price),
        evotorUuid = "uuid-$name",
        sendToReceipt = sendToReceipt,
    )

    @Test
    fun `print only option is excluded from the billable sum`() {
        val billable = opt("billable", "40", sendToReceipt = true)
        val printOnly = opt("print", "100", sendToReceipt = false)
        assertEquals(BigDecimal("40"), OptionPicker.billableSum(null, null, null, listOf(billable, printOnly)))
    }

    @Test
    fun `print only option is excluded from charged selection`() {
        val billable = opt("billable", "40", sendToReceipt = true)
        val printOnly = opt("print", "100", sendToReceipt = false)
        val selected = OptionPicker.billableSelection(null, null, null, listOf(billable, printOnly))
        assertTrue(selected.contains(billable))
        assertFalse(selected.contains(printOnly))
    }
}
