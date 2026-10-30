package ru.sixthcup.evotor.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.BuildConfig

class StoreUuidGuardTest {
    @Test
    fun `app uuid is never a store uuid`() {
        assertFalse(StoreUuidResolver.isStoreUuid(BuildConfig.APP_UUID))
        assertFalse(StoreUuidResolver.isStoreUuid(BuildConfig.APP_UUID.uppercase()))
    }

    @Test
    fun `sales point uuid is accepted, garbage rejected`() {
        assertTrue(StoreUuidResolver.isStoreUuid("20261017-AD69-40F0-80D2-3C027B1A4BF8"))
        assertFalse(StoreUuidResolver.isStoreUuid("not-a-uuid"))
        assertFalse(StoreUuidResolver.isStoreUuid(null))
    }
}
