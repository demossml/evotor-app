package ru.sixthcup.evotor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Панель отладки конфигурации: строки собираются из состояния без Android и без NPE. */
class ConfigSheetDebugTest {

    private fun state(
        phase: ConfigSheetDebug.Phase = ConfigSheetDebug.Phase.DONE,
        productId: Int? = 5,
        store: String? = "20261017-AD69-40F0-80D2-3C027B1A4BF8",
        requested: Boolean = true,
        http: Int? = 200,
        error: String? = null,
        volumes: Int = 3,
        milk: Int? = 2,
        additions: Int? = 17,
        errors: List<String> = emptyList(),
        uiMilk: Int = 2,
        uiSyrup: Int = 8,
        uiSugar: Int = 3,
        uiExtra: Int = 6,
        needOpts: Boolean = true,
        configPresent: Boolean = true,
        matchBy: String = "productId",
        pid: Int? = 5,
    ) = ConfigSheetDebug.State(
        versionName = "5.2.29-config-debug-ui", versionCode = 155, phase = phase,
        productId = productId, storeUuid = store, requested = requested,
        httpStatus = http, elapsedMs = 42, error = error, volumes = volumes,
        milkOptions = milk, additionOptions = additions, configErrors = errors,
        matchBy = matchBy, matchPid = pid, terminalUuid = "TERMINAL-UUID-350",
        uiMilk = uiMilk, uiSyrup = uiSyrup, uiSugar = uiSugar, uiExtra = uiExtra,
        needOpts = needOpts, configPresent = configPresent,
    )

    @Test
    fun `seven lines are built from a successful fetch`() {
        val lines = ConfigSheetDebug.lines(state())
        assertEquals(7, lines.size)
        assertTrue(lines[0], lines[0].startsWith("1. Sheet open"))
        assertTrue(lines[0], lines[0].contains("(155)"))
        assertTrue(lines[1], lines[1].contains("productId=5"))
        assertTrue(lines[2], lines[2].contains("HTTP 200"))
        assertTrue(lines[2], lines[2].contains("42ms"))
        assertTrue(lines[3], lines[3].contains("volumes=3"))
        assertTrue(lines[3], lines[3].contains("milk=2"))
        assertTrue(lines[3], lines[3].contains("additions=17"))
        assertTrue(lines[4], lines[4].contains("by=productId"))
        assertTrue(lines[5], lines[5].contains("milk=2"))
        assertTrue(lines[5], lines[5].contains("extra=6"))
        assertTrue(lines[6], lines[6].contains("needOpts=true"))
    }

    @Test
    fun `waiting state does not claim an answer`() {
        val lines = ConfigSheetDebug.lines(state(phase = ConfigSheetDebug.Phase.WAITING, http = null, volumes = 0))
        assertTrue(lines[2], lines[2].contains("WAIT"))
        assertEquals("4. Body: —", lines[3])
    }

    @Test
    fun `http error is printed with its code`() {
        val lines = ConfigSheetDebug.lines(
            state(phase = ConfigSheetDebug.Phase.FAILED, http = 404, error = "HTTP 404: маршрута нет"),
        )
        assertTrue(lines[2], lines[2].startsWith("3. ERROR:"))
        assertTrue(lines[2], lines[2].contains("404"))
    }

    @Test
    fun `silent failure also gets an ERROR line`() {
        val lines = ConfigSheetDebug.lines(state(error = "config_null"))
        assertTrue(lines.toString(), lines.any { it == "ERROR: config_null" })
    }

    @Test
    fun `skipped request explains why`() {
        val noPid = ConfigSheetDebug.lines(state(productId = null, requested = false))
        assertTrue(noPid[1], noPid[1].contains("нет productId"))
        val noStore = ConfigSheetDebug.lines(state(store = null, requested = false))
        assertTrue(noStore[1], noStore[1].contains("storeUuid"))
    }

    @Test
    fun `null schemes are shown as null and config errors in brackets`() {
        val lines = ConfigSheetDebug.lines(state(milk = null, additions = null, errors = listOf("links")))
        assertTrue(lines[3], lines[3].contains("milk=null"))
        assertTrue(lines[3], lines[3].contains("additions=null"))
        assertTrue(lines[3], lines[3].contains("errors=[links]"))
    }

    @Test
    fun `long identifiers are shortened`() {
        val lines = ConfigSheetDebug.lines(state())
        assertTrue(lines[1], lines[1].contains("…"))
        assertTrue(lines[1].length < 80)
    }

    @Test
    fun `from cache is visible`() {
        val s = state().copy(fromCache = true)
        assertTrue(ConfigSheetDebug.lines(s)[2].contains("fromCache=true"))
    }
}
