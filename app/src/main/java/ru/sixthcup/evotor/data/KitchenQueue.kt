package ru.sixthcup.evotor.data

import ru.sixthcup.evotor.net.KitchenApi

/**
 * Состояние последнего kitchen-заказа на этой кассе и одна повторная попытка create.
 *
 * Живёт в памяти: касса показывает номер и статус текущего заказа, историю хранит backend.
 * Ошибка сети не должна ломать продажу — [createWithRetry] возвращает null и пишет лог.
 */
object KitchenQueue {

    data class Last(
        val orderId: String,
        val queueNumber: String,
        val status: String,
        val at: Long = System.currentTimeMillis(),
    ) {
        val done: Boolean get() = KitchenApi.isDone(status)
    }

    @Volatile
    private var last: Last? = null

    fun last(): Last? = last

    fun remember(created: KitchenApi.Created) {
        last = Last(created.id, created.queueNumber, created.status)
    }

    /** Обновить статус, если он относится к текущему заказу. */
    fun applyStatus(update: KitchenApi.Status) {
        val cur = last ?: return
        if (cur.orderId != update.id) return
        last = cur.copy(status = update.status.ifBlank { cur.status })
    }

    fun clear() {
        last = null
    }

    /**
     * Одна повторная попытка при сетевой/серверной ошибке. `attempts = 2` (первая + retry).
     * Финальный 4xx не повторяется. Возвращает последний результат.
     */
    internal fun attempt(
        attempts: Int = 2,
        send: (Int) -> KitchenApi.Result<KitchenApi.Created>,
    ): KitchenApi.Result<KitchenApi.Created> {
        var result: KitchenApi.Result<KitchenApi.Created> =
            KitchenApi.Result.Err("not started", retryable = false)
        var i = 1
        while (i <= attempts.coerceAtLeast(1)) {
            result = send(i)
            if (result is KitchenApi.Result.Ok) return result
            if (result is KitchenApi.Result.Err && !result.retryable) return result
            i++
        }
        return result
    }

    /** create c ретраем; null — заказ не создан (продажа уже прошла, откатывать нечего). */
    fun createWithRetry(
        clientOrderId: String,
        storeUuid: String,
        lines: List<KitchenApi.Line>,
        totalKopecks: Long?,
        queueOrderId: String?,
        onError: (String) -> Unit = {},
    ): KitchenApi.Created? {
        val result = attempt {
            KitchenApi.create(
                clientOrderId = clientOrderId,
                storeUuid = storeUuid,
                lines = lines,
                totalKopecks = totalKopecks,
                queueOrderId = queueOrderId,
            )
        }
        return when (result) {
            is KitchenApi.Result.Ok -> result.value.also { remember(it) }
            is KitchenApi.Result.Err -> {
                onError("kitchen not created: ${result.error} status=${result.httpStatus}")
                null
            }
        }
    }
}
