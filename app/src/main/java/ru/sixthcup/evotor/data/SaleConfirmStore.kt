package ru.sixthcup.evotor.data

import android.content.Context

/**
 * Сигнал «чек продажи реально напечатан» из `ReceiptPrintExtraService`.
 *
 * Это единственный доступный APK признак успешной фискализации: PrintExtra Эвотор вызывает
 * при печати чека, а аннулированный/отменённый чек не печатается. По нему `mark-paid`
 * не уходит за заказ, который касса не оплатила.
 */
object SaleConfirmStore {
    private const val PREF = "sixthcup_sale_confirm"
    private const val KEY_AT = "at"
    private const val KEY_PREORDER = "preorder"
    private const val TTL_MS = 30 * 60 * 1000L

    /** Печать под текущую продажу: запоминаем, какой предзаказ был в чеке (может быть null). */
    fun markPrinted(ctx: Context, preorderId: String?) {
        val editor = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        if (preorderId.isNullOrBlank()) {
            // Продажа без предзаказа стирает прошлый маркер, чтобы он не «прилип» к следующей.
            editor.clear().apply()
            return
        }
        editor.putString(KEY_PREORDER, preorderId)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun printedFor(ctx: Context, preorderId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (preorderId.isBlank()) return false
        val p = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val stored = p.getString(KEY_PREORDER, null) ?: return false
        val at = p.getLong(KEY_AT, 0L)
        if (at == 0L) return false
        if (nowMs - at > TTL_MS) return false
        return stored == preorderId
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
