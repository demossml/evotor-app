package ru.sixthcup.evotor.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Серверная витрина рассыпалась на отдельные объёмы (регресс 5.2.22), поэтому она выключена
 * в кассе, даже если backend присылает `features.serverCatalog = true`.
 */
class CatalogModeTest {

    @Test
    fun `server catalog stays off in the current build`() {
        assertFalse(CatalogMode.useServerCatalog(true))
        assertFalse(CatalogMode.useServerCatalog(false))
        assertFalse(CatalogMode.useServerCatalog(null))
    }

    @Test
    fun `when allowed the backend flag is respected`() {
        assertTrue(CatalogMode.useServerCatalog(true, allowed = true))
        assertFalse(CatalogMode.useServerCatalog(false, allowed = true))
        assertFalse(CatalogMode.useServerCatalog(null, allowed = true))
    }
}
