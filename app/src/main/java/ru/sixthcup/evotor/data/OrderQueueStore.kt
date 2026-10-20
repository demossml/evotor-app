package ru.sixthcup.evotor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                    status = if (o.optString("st") == "READY") OrderStatus.READY else OrderStatus.PREPARING,
                    readyAt = if (o.has("rt") && !o.isNull("rt")) o.optLong("rt") else null,
                )
            }
        } catch (_: Throwable) { emptyList() }
        val pruned = QueueOps.prune(list, now)
        if (pruned.size != list.size) save(c, pruned)
        return pruned
    }

    @Synchronized
    fun save(c: Context, list: List<QueueOrder>) {
        try {
            val arr = JSONArray()
            list.forEach { q ->
                arr.put(JSONObject().put("n", q.number).put("at", q.createdAt)
                    .put("items", JSONArray(q.items)).put("sum", q.totalRub.toPlainString())
                    .put("st", q.status.name).put("rt", q.readyAt ?: JSONObject.NULL))
            }
            prefs(c).edit().putString(K_ORDERS, arr.toString()).apply()
        } catch (_: Throwable) {}
    }

    /**
     * Выдаёт номер новому заказу и кладёт его в очередь. [sessionNumber] — номер смены Эвотора или null.
     * Вызывать ровно один раз на оплаченный чек.
     */
    @Synchronized
    fun enqueue(c: Context, items: List<String>, totalRub: BigDecimal, sessionNumber: Long?, now: Long = System.currentTimeMillis()): QueueOrder {
        val p = prefs(c)
        val epoch = OrderNumbers.epochKey(sessionNumber, dayKey(now))
        val current = load(c, now)
        val (n, counter) = OrderNumbers.next(
            NumberCounter(p.getString(K_EPOCH, null), p.getInt(K_LAST, 0)),
            epoch,
            current.map { it.number }.toSet(),
        )
        p.edit().putString(K_EPOCH, counter.key).putInt(K_LAST, counter.last).apply()
        val order = QueueOrder(n, now, items, totalRub)
        save(c, QueueOps.add(current, order))
        recordReceipt(c, epoch, totalRub)
        return order
    }

    @Synchronized
    fun markReady(c: Context, number: Int, now: Long = System.currentTimeMillis()) = save(c, QueueOps.markReady(load(c, now), number, now))
    @Synchronized
    fun reopen(c: Context, number: Int) = save(c, QueueOps.reopen(load(c), number))
    @Synchronized
    fun remove(c: Context, number: Int) = save(c, QueueOps.remove(load(c), number))

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
