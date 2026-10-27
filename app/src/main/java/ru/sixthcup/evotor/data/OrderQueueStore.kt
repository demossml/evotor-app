package ru.sixthcup.evotor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Локальное хранилище очереди заказов, счётчика номеров и итогов смены.
 * Всё живёт на терминале (SharedPreferences) — для v1 бэкенд не нужен.
 * Любая ошибка чтения → пустая очередь (касса не должна падать из-за очереди).
 */
object OrderQueueStore {
    private const val PREF = "sixthcup_queue"
    private const val K_ORDERS = "orders"
    private const val K_EPOCH = "num_epoch"
    private const val K_LAST = "num_last"
    private const val K_T_EPOCH = "tally_epoch"
    private const val K_T_COUNT = "tally_count"
    private const val K_T_SUM = "tally_sum"
    private const val K_PENDING_ID = "pending_id"
    private const val K_SHOW_PENDING = "show_pending"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun dayKey(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    @Synchronized
    fun load(c: Context, now: Long = System.currentTimeMillis()): List<QueueOrder> {
        val list = try {
            val arr = JSONArray(prefs(c).getString(K_ORDERS, "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val items = o.optJSONArray("items") ?: JSONArray()
                QueueOrder(
                    number = o.getInt("n"),
                    createdAt = o.getLong("at"),
                    items = (0 until items.length()).map { items.getString(it) },
                    totalRub = BigDecimal(o.optString("sum", "0")),
                    status = statusOf(o.optString("st")),
                    readyAt = if (o.has("rt") && !o.isNull("rt")) o.optLong("rt") else null,
                    id = o.optString("id").ifBlank { legacyId(o.optLong("at"), o.optInt("n")) },
                    confirmedAt = if (o.has("ca") && !o.isNull("ca")) o.optLong("ca") else null,
                )
            }
        } catch (_: Throwable) { emptyList() }
        // TTL: PENDING без подтверждения (процесс убили) → EXPIRED, затем обычная очистка.
        val expired = QueueOps.expire(list, now)
        val pruned = QueueOps.prune(expired, now)
        if (pruned != list) save(c, pruned)
        return pruned
    }

    /**
     * Статус из хранилища. `PREPARING` — имя состояния «в работе» в версиях до 5.0.0:
     * читаем как CONFIRMED, чтобы старые заказы не потерялись и не стали фантомами.
     */
    private fun statusOf(raw: String): OrderStatus = when (raw) {
        "PENDING" -> OrderStatus.PENDING
        "READY" -> OrderStatus.READY
        "EXPIRED" -> OrderStatus.EXPIRED
        else -> OrderStatus.CONFIRMED
    }

    private fun legacyId(at: Long, n: Int): String = "legacy-$at-$n"

    @Synchronized
    fun save(c: Context, list: List<QueueOrder>) {
        try {
            val arr = JSONArray()
            list.forEach { q ->
                arr.put(JSONObject().put("n", q.number).put("at", q.createdAt)
                    .put("items", JSONArray(q.items)).put("sum", q.totalRub.toPlainString())
                    .put("st", q.status.name).put("rt", q.readyAt ?: JSONObject.NULL)
                    .put("id", q.id).put("ca", q.confirmedAt ?: JSONObject.NULL))
            }
            prefs(c).edit().putString(K_ORDERS, arr.toString()).apply()
        } catch (_: Throwable) {}
    }

    /**
     * Заказ ждёт подтверждения оплаты: номер НЕ присваивается, бариста его не видит.
     * Состав фиксируется в момент передачи позиций в чек. Идемпотентно по [id].
     */
    @Synchronized
    fun addPending(c: Context, items: List<String>, totalRub: BigDecimal, id: String = UUID.randomUUID().toString(), now: Long = System.currentTimeMillis()): QueueOrder {
        val order = QueueOrder(0, now, items, totalRub, OrderStatus.PENDING, null, id)
        save(c, QueueOps.addPending(load(c, now), order))
        prefs(c).edit().putString(K_PENDING_ID, id).apply()
        return order
    }

    /** id текущего PENDING-заказа (переживает убийство процесса), либо null. */
    @Synchronized
    fun pendingId(c: Context): String? {
        val id = prefs(c).getString(K_PENDING_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return if (QueueOps.hasPending(load(c), id)) id else { prefs(c).edit().remove(K_PENDING_ID).apply(); null }
    }

    /**
     * Оплата подтверждена (чек в POS закрыт): присваиваем номер и учитываем его в tally смены.
     * Идемпотентно — повторный вызов по тому же id номер второй раз не выдаёт.
     * Возвращает заказ или null, если подтверждать нечего.
     */
    @Synchronized
    fun confirm(
        c: Context,
        id: String,
        sessionNumber: Long?,
        now: Long = System.currentTimeMillis(),
        /** Публичный номер с backend: он и попадает на чек. null — локальный счётчик (offline). */
        publicNumber: Int? = null,
    ): QueueOrder? {
        val current = load(c, now)
        if (!QueueOps.hasPending(current, id)) {
            prefs(c).edit().remove(K_PENDING_ID).apply()
            return null
        }
        val p = prefs(c)
        val epoch = OrderNumbers.epochKey(sessionNumber, dayKey(now))
        val (n, counter) = OrderNumbers.next(
            NumberCounter(p.getString(K_EPOCH, null), p.getInt(K_LAST, 0)),
            epoch,
            QueueOps.activeNumbers(current),
        )
        val number = publicNumber?.takeIf { it > 0 } ?: n
        p.edit().putString(K_EPOCH, counter.key).putInt(K_LAST, counter.last).remove(K_PENDING_ID).apply()
        val updated = QueueOps.confirm(current, id, number, now)
        save(c, updated)
        val order = updated.firstOrNull { it.id == id }
        if (order != null) recordReceipt(c, epoch, order.totalRub)
        return order
    }

    /** Оплата не прошла / чек остался открытым: PENDING удаляется, номер не израсходован. */
    @Synchronized
    fun cancelPending(c: Context, id: String) {
        save(c, QueueOps.cancelPending(load(c), id))
        prefs(c).edit().remove(K_PENDING_ID).apply()
    }

    /** Точки, где начинают готовить до оплаты: PENDING виден баристе. По умолчанию выключено. */
    fun showPending(c: Context): Boolean = prefs(c).getBoolean(K_SHOW_PENDING, false)
    fun setShowPending(c: Context, on: Boolean) { prefs(c).edit().putBoolean(K_SHOW_PENDING, on).apply() }

    @Synchronized
    fun markReady(c: Context, number: Int, now: Long = System.currentTimeMillis()) = save(c, QueueOps.markReady(load(c, now), number, now))
    @Synchronized
    fun reopen(c: Context, number: Int) = save(c, QueueOps.reopen(load(c), number))
    @Synchronized
    fun remove(c: Context, number: Int) = save(c, QueueOps.remove(load(c), number))

    @Synchronized
    fun removeById(c: Context, id: String) = save(c, QueueOps.removeById(load(c), id))

    private fun recordReceipt(c: Context, epoch: String, amount: BigDecimal) {
        val p = prefs(c)
        val cur = ShiftTally(p.getString(K_T_EPOCH, null), p.getInt(K_T_COUNT, 0), BigDecimal(p.getString(K_T_SUM, "0") ?: "0"))
        val n = cur.add(epoch, amount)
        p.edit().putString(K_T_EPOCH, n.epoch).putInt(K_T_COUNT, n.receipts).putString(K_T_SUM, n.sumRub.toPlainString()).apply()
    }

    /** Итоги текущей смены с этого терминала. */
    fun tally(c: Context, sessionNumber: Long?, now: Long = System.currentTimeMillis()): ShiftTally {
        val p = prefs(c)
        val cur = ShiftTally(p.getString(K_T_EPOCH, null), p.getInt(K_T_COUNT, 0), try { BigDecimal(p.getString(K_T_SUM, "0") ?: "0") } catch (_: Throwable) { BigDecimal.ZERO })
        return cur.forEpoch(OrderNumbers.epochKey(sessionNumber, dayKey(now)))
    }
}
