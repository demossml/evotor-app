package ru.sixthcup.evotor.data

import java.math.BigDecimal

/**
 * Очередь бариста — чистая логика (без Android), покрыта юнит-тестами.
 * Хранение — [OrderQueueStore] (SharedPreferences, только на этом терминале).
 */
enum class OrderStatus { PREPARING, READY }

data class QueueOrder(
    /** Короткий номер to-go 1…99 (на экране «01»). */
    val number: Int,
    val createdAt: Long,
    /** Состав для бариста: «Капучино × 2», «Круассан». */
    val items: List<String>,
    val totalRub: BigDecimal,
    val status: OrderStatus = OrderStatus.PREPARING,
    val readyAt: Long? = null,
) {
    val label: String get() = OrderNumbers.format(number)
}

/** Счётчик номеров. [key] — «эпоха» сброса, см. [OrderNumbers.epochKey]. */
data class NumberCounter(val key: String?, val last: Int)

object OrderNumbers {
    const val MAX = 99

    /**
     * ЕДИНОЕ правило сброса: нумерация начинается с 01 при смене «эпохи».
     * Эпоха = номер смены Эвотора (`SystemStateApi.getLastSessionNumber`), если он известен —
     * новая смена ⇒ новая нумерация; если Эвотор номер не отдал — календарные сутки кассы (00:00).
     */
    fun epochKey(sessionNumber: Long?, dayKey: String): String =
        if (sessionNumber != null) "s$sessionNumber" else "d$dayKey"

    /**
     * Следующий номер. Новая эпоха → 1. После 99 — снова с 1, пропуская номера, которые ещё в очереди
     * (чтобы два живых заказа не имели одинаковый номер). Если заняты все 99 — берём следующий по кругу.
     */
    fun next(counter: NumberCounter, epoch: String, active: Set<Int>): Pair<Int, NumberCounter> {
        val start = if (counter.key == epoch) counter.last else 0
        var n = start
        repeat(MAX) {
            n = if (n >= MAX) 1 else n + 1
            if (n !in active) return n to NumberCounter(epoch, n)
        }
        val fallback = if (start >= MAX) 1 else start + 1
        return fallback to NumberCounter(epoch, fallback)
    }

    fun format(n: Int): String = n.toString().padStart(2, '0')
}

object QueueOps {
    const val READY_TTL_MS = 15 * 60 * 1000L
    const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    fun add(list: List<QueueOrder>, order: QueueOrder): List<QueueOrder> =
        list.filter { it.number != order.number } + order

    /** «Готово»: PREPARING → READY. Повторно/по неизвестному номеру — без изменений. */
    fun markReady(list: List<QueueOrder>, number: Int, now: Long): List<QueueOrder> =
        list.map { if (it.number == number && it.status == OrderStatus.PREPARING) it.copy(status = OrderStatus.READY, readyAt = now) else it }

    /** «Вернуть в работу»: READY → PREPARING. */
    fun reopen(list: List<QueueOrder>, number: Int): List<QueueOrder> =
        list.map { if (it.number == number && it.status == OrderStatus.READY) it.copy(status = OrderStatus.PREPARING, readyAt = null) else it }

    fun remove(list: List<QueueOrder>, number: Int): List<QueueOrder> = list.filter { it.number != number }

    /** Убираем выданные (READY дольше [READY_TTL_MS]) и совсем старые заказы (забытые, > [MAX_AGE_MS]). */
    fun prune(list: List<QueueOrder>, now: Long): List<QueueOrder> = list.filter { o ->
        val tooOld = now - o.createdAt > MAX_AGE_MS
        val handed = o.status == OrderStatus.READY && o.readyAt != null && now - o.readyAt > READY_TTL_MS
        !tooOld && !handed
    }

    fun preparing(list: List<QueueOrder>): List<QueueOrder> =
        list.filter { it.status == OrderStatus.PREPARING }.sortedBy { it.createdAt }

    fun ready(list: List<QueueOrder>): List<QueueOrder> =
        list.filter { it.status == OrderStatus.READY }.sortedByDescending { it.readyAt ?: 0L }
}

/** Итоги текущей смены по чекам, пробитым с этого терминала (локальный счётчик, не данные Эвотора). */
data class ShiftTally(val epoch: String?, val receipts: Int, val sumRub: BigDecimal) {
    fun add(epochNow: String, amount: BigDecimal): ShiftTally {
        val base = if (epoch == epochNow) this else ShiftTally(epochNow, 0, BigDecimal.ZERO)
        return ShiftTally(epochNow, base.receipts + 1, base.sumRub.add(amount))
    }
    /** Для показа: если эпоха сменилась — нули. */
    fun forEpoch(epochNow: String): ShiftTally = if (epoch == epochNow) this else ShiftTally(epochNow, 0, BigDecimal.ZERO)
    val avgRub: BigDecimal? get() = if (receipts > 0) sumRub.divide(BigDecimal(receipts), 0, java.math.RoundingMode.HALF_UP) else null
}
