package ru.sixthcup.evotor.net

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * API смены 6.7: табель, пожелания, рекомендация перерыва, команда, сводка.
 * Контракт — BACKEND-API.md. Вызывать с фона. Ничего не подставляем «вместо» ответа:
 * сеть/HTTP-ошибка → [Res.Fail], пустой ответ → пустой список/null (UI показывает «нет данных»).
 */
object ShiftApi {
    sealed class Res<out T> {
        data class Ok<T>(val value: T) : Res<T>()
        data class Fail(val message: String) : Res<Nothing>()
    }

    // ── модели ──
    /** Один день табеля текущего сотрудника. status: work | off | vacation | sick. */
    data class TsDay(val date: String, val status: String, val from: String?, val to: String?, val note: String?) {
        /** Номер дня месяца из `yyyy-MM-dd`. */
        val day: Int get() = date.takeLast(2).toIntOrNull() ?: 0
    }

    /** status: open | sent | approved | rejected | closed. */
    data class Wishes(val month: String, val status: String, val days: List<String>, val editable: Boolean, val limit: Int?, val deadline: String?)

    data class BreakRec(val from: String, val to: String, val reason: String?)

    data class Member(val name: String, val role: String?, val receipts: Int?, val revenueRub: Int?)

    data class Summary(
        val receipts: Int?, val revenueRub: Int?, val avgRub: Int?, val drinksPct: Int?,
        val hourly: List<Pair<Int, Int>>, val top: List<Pair<String, Int>>,
    )

    // ── запросы ──
    fun timesheet(fromIso: String, toIso: String): Res<List<TsDay>> =
        call("GET", "/api/devices/timesheet?from=$fromIso&to=$toIso", null).map { parseTimesheet(it) }

    fun wishes(month: String): Res<Wishes> =
        call("GET", "/api/devices/timesheet/wishes?month=$month", null).map { parseWishes(it, month) }

    fun sendWishes(month: String, days: List<String>): Res<Wishes> {
        val body = JSONObject().put("month", month).put("days", JSONArray(days))
        return call("POST", "/api/devices/timesheet/wishes", body.toString()).map { parseWishes(it, month) }
    }

    fun breakRecommendation(): Res<BreakRec?> =
        call("GET", "/api/devices/breaks/recommendation", null).map { parseBreak(it) }

    fun team(): Res<List<Member>> = call("GET", "/api/devices/shift/team", null).map { parseTeam(it) }

    fun summary(): Res<Summary> = call("GET", "/api/devices/shift/summary", null).map { parseSummary(it) }

    // ── разбор (чистые функции, покрыты тестами) ──
    fun parseTimesheet(o: JSONObject): List<TsDay> {
        val a = o.optJSONArray("days") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val d = a.optJSONObject(i) ?: return@mapNotNull null
            val date = d.optString("date")
            if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)) return@mapNotNull null
            TsDay(date, d.optString("status", "work"), d.optStr("from"), d.optStr("to"), d.optStr("note"))
        }
    }

    fun parseWishes(o: JSONObject, month: String): Wishes {
        val a = o.optJSONArray("days") ?: JSONArray()
        val status = o.optString("status", "open").ifBlank { "open" }
        return Wishes(
            month = o.optStr("month") ?: month,
            status = status,
            days = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() },
            editable = if (o.has("editable")) o.optBoolean("editable") else status == "open" || status == "draft",
            limit = if (o.has("limit") && !o.isNull("limit")) o.optInt("limit") else null,
            deadline = o.optStr("deadline"),
        )
    }

    fun parseBreak(o: JSONObject): BreakRec? {
        val r = if (o.has("recommendation")) o.optJSONObject("recommendation") else o
        if (r == null) return null
        val from = r.optStr("from") ?: return null
        val to = r.optStr("to") ?: return null
        return BreakRec(from, to, r.optStr("reason"))
    }

    fun parseTeam(o: JSONObject): List<Member> {
        val a = o.optJSONArray("people") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val p = a.optJSONObject(i) ?: return@mapNotNull null
            val name = p.optStr("name") ?: return@mapNotNull null
            Member(name, p.optStr("role"), p.optIntOrNull("receipts"), p.optIntOrNull("revenueRub"))
        }
    }

    fun parseSummary(o: JSONObject): Summary {
        val h = o.optJSONArray("hourly") ?: JSONArray()
        val t = o.optJSONArray("top") ?: JSONArray()
        return Summary(
            receipts = o.optIntOrNull("receipts"), revenueRub = o.optIntOrNull("revenueRub"),
            avgRub = o.optIntOrNull("avgReceiptRub"), drinksPct = o.optIntOrNull("drinksSharePct"),
            hourly = (0 until h.length()).mapNotNull { i -> h.optJSONObject(i)?.let { it.optIntOrNull("hour")?.let { hr -> hr to (it.optIntOrNull("revenueRub") ?: 0) } } },
            top = (0 until t.length()).mapNotNull { i -> t.optJSONObject(i)?.let { x -> x.optStr("name")?.let { n -> n to (x.optIntOrNull("qty") ?: 0) } } },
        )
    }

    // ── HTTP ──
    private fun call(method: String, path: String, body: String?): Res<JSONObject> {
        return try {
            val conn = URL(BuildConfig.API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            DeviceHeaders.apply(conn)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (status in 200..299) {
                Res.Ok(if (text.isBlank()) JSONObject() else JSONObject(text))
            } else {
                val msg = try { JSONObject(text).optString("message") } catch (_: Throwable) { "" }
                Log.w("ShiftApi", "$method $path HTTP $status")
                Res.Fail(msg.ifBlank { if (status == 404) "Сервер пока не поддерживает этот раздел" else "Ошибка сервера ($status)" })
            }
        } catch (e: Throwable) {
            Log.w("ShiftApi", "$method $path failed: ${e.message}")
            Res.Fail("Нет связи с сервером")
        }
    }

    private inline fun <T, R> Res<T>.map(f: (T) -> R): Res<R> = when (this) {
        is Res.Ok -> try { Res.Ok(f(value)) } catch (e: Throwable) { Res.Fail("Некорректный ответ сервера") }
        is Res.Fail -> this
    }

    private fun JSONObject.optStr(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.optIntOrNull(k: String): Int? =
        if (!has(k) || isNull(k)) null else if (get(k) is Number) optDouble(k).toInt() else null
}
