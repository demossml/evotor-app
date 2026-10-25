package ru.sixthcup.evotor.data

import java.math.BigDecimal

/**
 * Очередь бариста — чистая логика (без Android), покрыта юнит-тестами.
 * Хранение — [OrderQueueStore] (SharedPreferences, только на этом терминале).
 */
enum class OrderStatus {
    /** Позиции переданы в Эвотор, оплата ещё НЕ подтверждена: номера нет, бариста не видит. */
    PENDING,

    /** Оплата подтверждена (чек в POS закрыт) — заказ в работе, номер присвоен. */
    CONFIRMED,

    /** Готов к выдаче. */
    READY,

    /** Чек так и не закрылся за [QueueOps.PENDING_TTL_MS] (приложение убили): фантом, не показываем. */
    EXPIRED;

    companion object {
        /**
         * Прежнее имя состояния «в работе». Оставлено для совместимости со старыми сохранёнными
         * данными и существующими тестами: подтверждённый оплатой заказ = CONFIRMED.
         */
        @Deprecated("Renamed to CONFIRMED", ReplaceWith("CONFIRMED"))
        val PREPARING: OrderStatus get() = CONFIRMED
    }
}

data class QueueOrder(
    /** Короткий номер to-go 1…99 (на экране «01»). 0 — ещё не присвоен (PENDING). */
    val number: Int,
    val createdAt: Long,
    /** Состав для бариста: «Капучино × 2», «Круассан». */
    val items: List<String>,
    val totalRub: BigDecimal,
    val status: OrderStatus = OrderStatus.CONFIRMED,
    val readyAt: Long? = null,
    /** Стабильный id записи: нужен, пока номер не присвоен. */
    val id: String = "",
    /** Момент подтверждения оплаты (для TTL готовых/просроченных). */
    val confirmedAt: Long? = null,
) {
    val label: String get() = if (number > 0) OrderNumbers.format(number) else "—"
    val isPending: Boolean get() = status == OrderStatus.PENDING
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
    /** Сколько PENDING-запись ждёт подтверждения оплаты, прежде чем стать EXPIRED. */
    const val PENDING_TTL_MS = 10 * 60 * 1000L

    fun add(list: List<QueueOrder>, order: QueueOrder): List<QueueOrder> =
        list.filter { it.number != order.number } + order

    /** Поставить заказ в ожидание оплаты: номер ещё не тратится, бариста его не видит. */
    fun addPending(list: List<QueueOrder>, order: QueueOrder): List<QueueOrder> =
        list.filter { it.id != order.id && it.number != order.number } +
            order.copy(status = OrderStatus.PENDING, number = 0, readyAt = null, confirmedAt = null)

    /**
     * Подтвердить оплату и присвоить номер. Идемпотентно: повторный вызов (возврат в onResume
     * ещё раз, повторный сигнал) ничего не меняет и номер не тратит.
     */
    fun confirm(list: List<QueueOrder>, id: String, number: Int, now: Long): List<QueueOrder> =
        list.map {
            if (it.id == id && it.status == OrderStatus.PENDING) {
                it.copy(status = OrderStatus.CONFIRMED, number = number, confirmedAt = now)
            } else it
        }

    /** Есть ли что подтверждать по этому id (PENDING). */
    fun hasPending(list: List<QueueOrder>, id: String): Boolean =
        list.any { it.id == id && it.status == OrderStatus.PENDING }

    /** Отмена: чек не закрылся. PENDING-запись удаляется, номер не израсходован. */
    fun cancelPending(list: List<QueueOrder>, id: String): List<QueueOrder> =
        list.filterNot { it.id == id && it.status == OrderStatus.PENDING }

    /** TTL: PENDING старше [ttlMs] становится EXPIRED (приложение убили — onResume не случился). */
    fun expire(list: List<QueueOrder>, now: Long, ttlMs: Long = PENDING_TTL_MS): List<QueueOrder> =
        list.map {
            if (it.status == OrderStatus.PENDING && now - it.createdAt >= ttlMs) {
                it.copy(status = OrderStatus.EXPIRED)
            } else it
        }

    /** Номера, занятые живыми заказами (для [OrderNumbers.next]); PENDING и EXPIRED не в счёт. */
    fun activeNumbers(list: List<QueueOrder>): Set<Int> =
        list.filter { (it.status == OrderStatus.CONFIRMED || it.status == OrderStatus.READY) && it.number > 0 }
            .map { it.number }.toSet()

    /** Заказы, которые учитывает tally смены: только подтверждённые оплатой. */
    fun countable(list: List<QueueOrder>): List<QueueOrder> =
        list.filter { it.status == OrderStatus.CONFIRMED || it.status == OrderStatus.READY }

    fun removeById(list: List<QueueOrder>, id: String): List<QueueOrder> = list.filter { it.id != id }

    /** «Готово»: CONFIRMED → READY. Повторно/по неизвестному номеру — без изменений. */
    fun markReady(list: List<QueueOrder>, number: Int, now: Long): List<QueueOrder> =
        list.map { if (it.number == number && it.status == OrderStatus.CONFIRMED) it.copy(status = OrderStatus.READY, readyAt = now) else it }

    /** «Вернуть в работу»: READY → CONFIRMED. */
    fun reopen(list: List<QueueOrder>, number: Int): List<QueueOrder> =
        list.map { if (it.number == number && it.status == OrderStatus.READY) it.copy(status = OrderStatus.CONFIRMED, readyAt = null) else it }

    fun remove(list: List<QueueOrder>, number: Int): List<QueueOrder> = list.filter { it.number != number }

    /**
     * Убираем выданные (READY дольше [READY_TTL_MS]), просроченные и совсем старые заказы.
     * EXPIRED хранится только до ближайшей очистки — фантомов в очереди не остаётся.
     */
    fun prune(list: List<QueueOrder>, now: Long): List<QueueOrder> = list.filter { o ->
        val tooOld = now - o.createdAt > MAX_AGE_MS
        val handed = o.status == OrderStatus.READY && o.readyAt != null && now - o.readyAt > READY_TTL_MS
        val expired = o.status == OrderStatus.EXPIRED
        !tooOld && !handed && !expired
    }

    /** Заказы в работе. PENDING виден только при включённом флаге точки (queueShowPending). */
    fun preparing(list: List<QueueOrder>, showPending: Boolean = false): List<QueueOrder> =
        list.filter {
            it.status == OrderStatus.CONFIRMED || (showPending && it.status == OrderStatus.PENDING)
        }.sortedBy { it.createdAt }

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
