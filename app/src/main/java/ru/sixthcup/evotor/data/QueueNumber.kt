package ru.sixthcup.evotor.data

import android.content.Context
import org.json.JSONObject

/**
 * Публичный номер заказа на чеке (Queue Number).
 *
 * Номер выдаёт **только сервер** (`POST /api/devices/queue-number`): локальные счётчики
 * на терминалах запрещены — иначе две кассы одной точки напечатают одинаковые номера.
 * Здесь хранится только «номер под текущую продажу», чтобы PrintExtra мог его напечатать.
 */
data class QueueNumber(
    val orderId: String,
    /** Как печатать: 1…3 цифры без ведущих нулей. */
    val publicNumber: String,
    val periodKey: String? = null,
    val expiresAt: Long = 0L,
    val issuedAt: Long = System.currentTimeMillis(),
) {
    /** Строка на чеке. Только кириллица и цифры, без фискальных реквизитов. */
    val printText: String get() = printTextFor(publicNumber)

    companion object {
        /** Дольше этого номер не считаем «своим» для новой продажи. */
        const val MAX_AGE_MS = 30 * 60 * 1000L

        fun printTextFor(number: String): String = "ЗАКАЗ № $number"

        /** Годится ли номер для печати в текущей продаже. */
        fun isUsable(n: QueueNumber?, now: Long = System.currentTimeMillis()): Boolean {
            if (n == null) return false
            if (n.publicNumber.isBlank()) return false
            if (!n.publicNumber.all { it.isDigit() }) return false
            if (now - n.issuedAt > MAX_AGE_MS) return false
            if (n.expiresAt > 0L && now > n.expiresAt) return false
            return true
        }

        fun parse(body: String): QueueNumber? {
            return try {
                val o = JSONObject(body)
                if (!o.optBoolean("ok")) return null
                val number = o.optString("publicOrderNumber").trim()
                val orderId = o.optString("orderId").trim()
                if (number.isEmpty() || orderId.isEmpty() || !number.all { it.isDigit() }) null
                else QueueNumber(
                    orderId = orderId,
                    publicNumber = number,
                    periodKey = o.optString("periodKey").ifBlank { null },
                    expiresAt = o.optLong("expiresAt", 0L),
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/**
 * Номер «на текущую продажу»: переживает перезапуск приложения и переживает печать
 * (печатается ровно один раз на чек). Хранится в SharedPreferences.
 */
object QueueNumberStore {
    private const val PREF = "sixthcup_queue_number"
    private const val KEY = "pending"

    fun save(ctx: Context, number: QueueNumber) {
        val o = JSONObject()
            .put("orderId", number.orderId)
            .put("publicNumber", number.publicNumber)
            .put("periodKey", number.periodKey ?: JSONObject.NULL)
            .put("expiresAt", number.expiresAt)
            .put("issuedAt", number.issuedAt)
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, o.toString()).apply()
    }

    fun peek(ctx: Context): QueueNumber? = try {
        val s = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
        if (s.isNullOrBlank()) null
        else {
            val o = JSONObject(s)
            QueueNumber(
                orderId = o.optString("orderId"),
                publicNumber = o.optString("publicNumber"),
                periodKey = if (o.isNull("periodKey")) null else o.optString("periodKey"),
                expiresAt = o.optLong("expiresAt", 0L),
                issuedAt = o.optLong("issuedAt", 0L),
            ).takeIf { QueueNumber.isUsable(it) }
        }
    } catch (_: Throwable) {
        null
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
