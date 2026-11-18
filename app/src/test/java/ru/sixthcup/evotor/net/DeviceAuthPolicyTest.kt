package ru.sixthcup.evotor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuthPolicyTest {

    @Test
    fun `request without store uuid is blocked with a clear code`() {
        assertEquals(DeviceAuthPolicy.MISSING_STORE, DeviceAuthPolicy.blockedError(null))
        assertEquals(DeviceAuthPolicy.MISSING_STORE, DeviceAuthPolicy.blockedError("  "))
        assertNull(DeviceAuthPolicy.blockedError("20261017-AD69-40F0-80D2-3C027B1A4BF8"))
    }

    @Test
    fun `401 and 403 are auth failures, not network errors`() {
        assertTrue(DeviceAuthPolicy.authFailure(401))
        assertTrue(DeviceAuthPolicy.authFailure(403))
        assertFalse(DeviceAuthPolicy.authFailure(500))
        assertFalse(DeviceAuthPolicy.authFailure(0))
    }

    @Test
    fun `every HTTP error drops an unconfirmed card`() {
        assertTrue(DeviceAuthPolicy.dropCard(400))
        assertTrue(DeviceAuthPolicy.dropCard(401))
        assertTrue(DeviceAuthPolicy.dropCard(403))
        assertTrue(DeviceAuthPolicy.dropCard(409))
        assertTrue(DeviceAuthPolicy.dropCard(429))
        assertTrue(DeviceAuthPolicy.dropCard(503))
        assertFalse(DeviceAuthPolicy.dropCard(0))
    }

    @Test
    fun `messages are human readable`() {
        assertTrue(DeviceAuthPolicy.missingStoreMessage().contains("точка продаж"))
        assertTrue(DeviceAuthPolicy.authMessage(401).contains("не авторизован"))
    }
}
