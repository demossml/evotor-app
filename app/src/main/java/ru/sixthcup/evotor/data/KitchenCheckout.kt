package ru.sixthcup.evotor.data

import android.content.Context

/**
 * `clientOrderId` для kitchen-заказа: один id на одну попытку оформления.
 *
 * Привязка идёт к PENDING-заказу локальной очереди ([OrderQueueStore]): он создаётся в момент,
 * когда состав корзины уже зафиксирован, и живёт до подтверждения оплаты.
 *
 * Правила:
 *  - повтор отправки в рамках одной попытки — **тот же** id (backend не создаёт дубль);
 *  - новая попытка (новый PENDING, в том числе после clear корзины) — **новый** id;
 *  - процесс убили между чеком и подтверждением — id восстанавливается из SharedPreferences.
 */
object KitchenCheckout {
    private const val PREF = "sixthcup_kitchen_checkout"
    private const val KEY_PENDING = "pending_id"
    private const val KEY_CLIENT = "client_order_id"

    private var ctx: Context? = null
    private var pendingId: String? = null
    private var clientId: String? = null

    fun attach(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        restore()
    }

    /** Запомнить id для попытки [pendingId]; повторный вызов с тем же pending id ничего не меняет. */
    fun bind(pendingId: String, newId: String = java.util.UUID.randomUUID().toString()): String {
        val (id, changed) = resolve(this.pendingId, clientId, pendingId, newId)
        if (changed || id != clientId) save(pendingId, id)
        return id
    }

    /** id текущей попытки или null, если id ещё не заводили. */
    fun current(pendingId: String?): String? {
        val key = pendingId?.takeIf { it.isNotBlank() } ?: return null
        return clientId?.takeIf { this.pendingId == key }
    }

    /** Попытка закрыта (kitchen-заказ создан) — следующий заказ получит новый id. */
    fun forget() {
        pendingId = null
        clientId = null
        ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
    }

    /**
     * Чистое решение: (id, изменилось ли состояние).
     * Тот же pending и уже есть id → id сохраняется; иначе — новый.
     */
    internal fun resolve(
        storedPending: String?,
        storedClient: String?,
        pendingId: String,
        newId: String,
    ): Pair<String, Boolean> {
        if (!storedClient.isNullOrBlank() && storedPending == pendingId) return storedClient to false
        return newId to true
    }

    private fun save(pending: String, client: String) {
        pendingId = pending
        clientId = client
        val c = ctx ?: return
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING, pending)
                .putString(KEY_CLIENT, client)
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun restore() {
        val c = ctx ?: return
        try {
            val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            pendingId = p.getString(KEY_PENDING, null)?.takeIf { it.isNotBlank() }
            clientId = p.getString(KEY_CLIENT, null)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            pendingId = null
            clientId = null
        }
    }
}
