package ru.sixthcup.evotor.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D-10: случайный extra не должен становиться кодом карты. */
class ScannerReceiverTest {

    @Test
    fun `official ScannedCode is accepted`() {
        assertEquals("payload.sig", ScannerReceiver.codeFromStrings(mapOf("ScannedCode" to "  payload.sig ")))
    }

    @Test
    fun `known legacy key is accepted`() {
        assertEquals("1234", ScannerReceiver.codeFromStrings(mapOf("barcode" to "1234")))
    }

    @Test
    fun `unknown extra is ignored`() {
        assertNull(ScannerReceiver.codeFromStrings(mapOf("randomExtra" to "payload.sig")))
        assertNull(ScannerReceiver.codeFromStrings(mapOf("action" to "ru.evotor.devices.ScannedCode")))
    }

    @Test
    fun `too long value is ignored`() {
        assertNull(ScannerReceiver.codeFromStrings(mapOf("ScannedCode" to "x".repeat(4001))))
    }
}
