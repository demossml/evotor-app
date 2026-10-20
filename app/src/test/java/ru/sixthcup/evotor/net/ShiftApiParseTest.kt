package ru.sixthcup.evotor.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftApiParseTest {
    @Test fun timesheetSkipsBadDatesAndHandlesEmpty() {
        val d = ShiftApi.parseTimesheet(JSONObject("""{"days":[{"date":"2026-10-18","status":"work","from":"08:00","to":"16:00"},{"date":"bad"}]}"""))
        assertEquals(1, d.size); assertEquals(18, d[0].day); assertEquals("08:00", d[0].from)
        assertTrue(ShiftApi.parseTimesheet(JSONObject("{}")).isEmpty())
    }

    @Test fun wishesDefaults() {
        val w = ShiftApi.parseWishes(JSONObject("""{"status":"approved","days":["2026-11-08"]}"""), "2026-11")
        assertEquals(false, w.editable); assertEquals(listOf("2026-11-08"), w.days)
        val open = ShiftApi.parseWishes(JSONObject("{}"), "2026-11")
        assertEquals("open", open.status); assertTrue(open.editable); assertNull(open.limit)
    }

    @Test fun breakRecommendation() {
        val r = ShiftApi.parseBreak(JSONObject("""{"from":"14:30","to":"14:50","reason":"затишье"}"""))!!
        assertEquals("14:30", r.from); assertEquals("затишье", r.reason)
        assertNull(ShiftApi.parseBreak(JSONObject("""{"recommendation":null}""")))
        assertNull(ShiftApi.parseBreak(JSONObject("{}")))
    }

    @Test fun teamAndSummaryWithMissingFields() {
        val t = ShiftApi.parseTeam(JSONObject("""{"people":[{"name":"Анна","receipts":3},{"role":"x"}]}"""))
        assertEquals(1, t.size); assertEquals(3, t[0].receipts); assertNull(t[0].revenueRub)
        val s = ShiftApi.parseSummary(JSONObject("""{"receipts":5,"hourly":[{"hour":9,"revenueRub":100}],"top":[{"name":"Капучино","qty":2}]}"""))
        assertEquals(5, s.receipts); assertNull(s.revenueRub); assertEquals(listOf(9 to 100), s.hourly); assertEquals(listOf("Капучино" to 2), s.top)
    }
}
