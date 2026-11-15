package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-02: товар с серверными схемами нельзя продать без configuration. */
class ProductConfigGuardTest {

    private val ref = VolumeRef(evotorUuid = "VOL-1")

    @Test
    fun `needsConfig without configuration is blocked`() {
        assertEquals(
            listOf(ProductConfigGuard.CONFIG_UNAVAILABLE),
            ProductConfigGuard.blockers(null, ref, needsConfig = true),
        )
    }

    @Test
    fun `legacy product without configuration is allowed`() {
        assertTrue(ProductConfigGuard.blockers(null, ref, needsConfig = false).isEmpty())
    }

    @Test
    fun `required milk without scheme is still blocked when configuration exists`() {
        val cfg = ProductConfig(
            schemaVersion = 1,
            storeUuid = "S",
            productId = 501,
            milk = MilkConfig(required = true, byProductId = emptyMap()),
        )
        val blocked = ProductConfigGuard.blockers(cfg, ref, needsConfig = true)
        assertTrue(blocked.toString(), blocked.any { it.contains("Нет схемы молока") })
    }
}
