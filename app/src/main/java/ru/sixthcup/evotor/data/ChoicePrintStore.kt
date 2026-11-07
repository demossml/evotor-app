package ru.sixthcup.evotor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Выбор без цены («Корица: с корицей») для печати на чеке продажи.
 *
 * Не фискальная позиция: 0 ₽ в чек пробить нельзя, поэтому выбор печатается строками
 * PrintExtra рядом с номером заказа. Данные одноразовые, как [SalePrintStore]:
 * сохраняются перед открытием чека, читаются в момент печати и сразу очищаются.
 * Поля [title]/[label] нужны и для будущего отдельного бумажного талона бариста.
 */
data class ChoicePrintEntry(
    val drink: String,
    val title: String,
    val label: String,
    val quantity: Int = 1,
)

object ChoicePrint {
    /** Ширина ленты чековых принтеров Эвотор в знаках (как у строки «ЗАКАЗ № …»). */
    const val WIDTH = 32

    /** Строки корзины с выбором → записи для печати; без выбора — пусто. */
    fun entriesFrom(lines: List<CartLine>): List<ChoicePrintEntry> =
        lines.mapNotNull { l ->
            val label = l.choiceLabel?.trim().orEmpty()
            if (label.isEmpty()) null
            else ChoicePrintEntry(
                drink = l.name,
                title = l.choiceTitle?.trim().orEmpty(),
                label = label,
                quantity = l.quantity.toInt().coerceAtLeast(1),
            )
        }

    /** Одна запись → строки ленты: «Напиток — Корица: с корицей ×2», перенос по словам. */
    fun linesFor(e: ChoicePrintEntry): List<String> {
        val head = if (e.title.isBlank()) e.label else "${e.title}: ${e.label}"
        val text = buildString {
            append(e.drink).append(" — ").append(head)
            if (e.quantity > 1) append(" ×").append(e.quantity)
        }
        return wrap(text, WIDTH)
    }

    fun lines(entries: List<ChoicePrintEntry>): List<String> = entries.flatMap { linesFor(it) }

    /** Перенос по пробелам; слово длиннее строки режется. Продолжение — с отступом в 2 пробела. */
    fun wrap(text: String, width: Int): List<String> {
        val out = ArrayList<String>(2)
        var cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out.add(cur.toString().trimEnd()); cur = StringBuilder() }
        for (raw in text.trim().split(Regex("\\s+"))) {
            var word = raw
            while (true) {
                val indent = if (out.isEmpty() && cur.isEmpty()) "" else if (cur.isEmpty()) "  " else ""
                val sep = if (cur.isEmpty()) indent else " "
                if (cur.length + sep.length + word.length <= width) {
                    cur.append(sep).append(word)
                    break
                }
                if (cur.isNotEmpty()) { flush(); continue }
                // слово не помещается даже в пустую строку
                val room = width - indent.length
                cur.append(indent).append(word.take(room))
                flush()
                word = word.drop(room)
                if (word.isEmpty()) break
            }
        }
        flush()
        return out
    }
}

object ChoicePrintStore {
    private const val PREF = "sixthcup_choice_print"
    private const val KEY = "entries"

    fun encode(entries: List<ChoicePrintEntry>): String = JSONArray().apply {
        entries.forEach {
            put(JSONObject().put("d", it.drink).put("t", it.title).put("l", it.label).put("q", it.quantity))
        }
    }.toString()

    fun decode(raw: String?): List<ChoicePrintEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val label = o.optString("l").trim()
                if (label.isEmpty()) null
                else ChoicePrintEntry(o.optString("d"), o.optString("t"), label, o.optInt("q", 1).coerceAtLeast(1))
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun save(ctx: Context, entries: List<ChoicePrintEntry>) {
        val prefs = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (entries.isEmpty()) prefs.edit().remove(KEY).apply()
        else prefs.edit().putString(KEY, encode(entries)).apply()
    }

    fun peek(ctx: Context): List<ChoicePrintEntry> =
        decode(ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null))

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
