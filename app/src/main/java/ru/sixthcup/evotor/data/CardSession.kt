package ru.sixthcup.evotor.data

import android.content.Context
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig

/**
 * Guest identified for the CURRENT sale only. The QR is just an identity; the balance and the
 * bonus reservation come from the backend (see LoyaltyApi). Nothing here is a source of truth.
 */
data class CardInfo(
    /** Raw scanned value: signed QR token or short numeric card number. */
    val code: String,
    val cardCode: String,
    val paidCups: Int,
    val cupsForFree: Int,
    val freeAvailable: Int,
    val cashbackKopecks: Long,
    val reservationId: String?,
    val reservationExpiresAt: Long,
    /** "RESERVED" | "ALREADY_RESERVED" | "NONE" | "OFFLINE" | "ERROR" */
    val freeStatus: String,
    val note: String? = null,
    /** Причина отказа backend: invalid_card | card_not_found | card_blocked (для журнала). */
    val errorCode: String? = null,
    /**
     * Sequence карты с сервера, если backend его отдаёт. Необязателен: без него чек пробивается
     * как раньше, `sc.q` просто не пишется.
     */
    val seq: Int? = null,
    /** Всего оплаченных чашек (`paidTotal`/`paid_total`), если сервер отдал. Точный прогресс. */
    val paidTotal: Int? = null,
    /** Сколько подарков уже использовано (`freeUsed`/`free_used`), если сервер отдал. */
    val freeUsed: Int? = null,
    /** Ваучеры гостя из resolve. Пусто — backend ещё не отдаёт поле, касса работает как раньше. */
    val vouchers: List<ResolveVoucher> = emptyList(),
)

object CardSession {
    private const val PREF = "sixthcup_card"
    private const val KEY = "info"
    private const val KEY_AT = "at"
    private const val KEY_STALE = "stale"
    /** A scan is only valid for one sale; stale scans must never leak into the next customer's receipt. */
    private const val TTL_MS = 10 * 60 * 1000L

    fun set(ctx: Context, info: CardInfo) {
        val o = JSONObject()
            .put("code", info.code).put("cardCode", info.cardCode).put("paidCups", info.paidCups)
            .put("cupsForFree", info.cupsForFree).put("freeAvailable", info.freeAvailable)
            .put("cb", info.cashbackKopecks).put("res", info.reservationId ?: JSONObject.NULL)
            .put("resExp", info.reservationExpiresAt).put("status", info.freeStatus).put("note", info.note ?: JSONObject.NULL)
            .put("seq", info.seq ?: JSONObject.NULL)
            .put("paidTotal", info.paidTotal ?: JSONObject.NULL)
            .put("freeUsed", info.freeUsed ?: JSONObject.NULL)
            .put("vouchers", VoucherJson.toJson(info.vouchers))
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, o.toString()).putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    fun get(ctx: Context): CardInfo? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val s = p.getString(KEY, null) ?: return null
        if (System.currentTimeMillis() - p.getLong(KEY_AT, 0L) > TTL_MS) { clear(ctx); return null }
        return try {
            val o = JSONObject(s)
            CardInfo(
                code = o.getString("code"), cardCode = o.optString("cardCode"), paidCups = o.optInt("paidCups"),
                cupsForFree = o.optInt("cupsForFree", 5), freeAvailable = o.optInt("freeAvailable"),
                cashbackKopecks = o.optLong("cb"), reservationId = if (o.isNull("res")) null else o.getString("res"),
                reservationExpiresAt = o.optLong("resExp"), freeStatus = o.optString("status", "NONE"),
                note = if (o.isNull("note")) null else o.getString("note"),
                seq = if (o.isNull("seq")) null else o.optInt("seq").takeIf { it > 0 },
                paidTotal = if (!o.has("paidTotal") || o.isNull("paidTotal")) null else o.optInt("paidTotal"),
                freeUsed = if (!o.has("freeUsed") || o.isNull("freeUsed")) null else o.optInt("freeUsed"),
                vouchers = VoucherJson.parse(o.optJSONArray("vouchers")),
            )
        } catch (_: Throwable) { clear(ctx); null }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Данные карты устарели (перезапрос не удался) — UI может это показать. */
    fun isStale(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_STALE, false)
    }

    private fun markStale(ctx: Context, stale: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_STALE, stale).apply()
    }

    /**
     * Перезапросить карту тем же credential (токен для QR, код для ручного ввода).
     *
     * 404/отказ сервера → сессия очищается; сеть/5xx → прежние данные остаются с пометкой
     * «устарели». `fetch` передаётся снаружи, чтобы логику можно было покрыть без сети.
     */
    fun refreshFromServer(ctx: Context, fetch: (String) -> CardInfo): RefreshResult {
        val current = get(ctx) ?: return RefreshResult(RefreshOutcome.NO_CARD, null)
        val credential = CardRefresh.credential(current) ?: return RefreshResult(RefreshOutcome.NO_CARD, null)
        val result = CardRefresh.apply(current, fetch(credential))
        when (result.outcome) {
            RefreshOutcome.UPDATED -> { set(ctx, result.info!!); markStale(ctx, stale = false) }
            RefreshOutcome.DROPPED -> clear(ctx)
            RefreshOutcome.KEPT_STALE -> markStale(ctx, stale = true)
            RefreshOutcome.NO_CARD -> Unit
        }
        return result
    }

    /**
     * Добавка к чеку: `{ appId: { sc: { v, c, op, free, cb, ts, kind } } }`.
     * Плоский `sc` продублирован для текущего SellHandler; значения в обоих ключах идентичны,
     * поэтому backend читает любую из форм и не применит льготу дважды (документ один).
     *
     * `v` — число ≥ 2 (иначе сервер игнорирует), `c` — подписанный QR-токен либо цифровой
     * код карты (см. [scValue]), `op` — reservationId (без него списание не применится),
     * `cb` — копейки, `ts` — unix ms.
     */
    fun extras(
        info: CardInfo,
        freeApplied: Boolean,
        cashbackKopecks: Long,
        queue: QueueNumber? = null,
        voucherId: Int? = null,
    ): JSONObject {
        val sc = sc(info, freeApplied, cashbackKopecks, queue, voucherId)
        // Плоский ключ нужен текущему парсеру: если appId не совпал с EVOTOR_APP_ID на backend,
        // льгота не потеряется. Оба значения одинаковые — двойного списания нет.
        return JSONObject()
            .put(BuildConfig.APP_UUID, JSONObject().put("sc", sc))
            .put("sc", sc)
    }

    /** extras.sc written into the receipt. The backend re-checks everything when it reads the SELL. */
    fun sc(
        info: CardInfo,
        freeApplied: Boolean,
        cashbackKopecks: Long,
        /** Публичный номер заказа: связывает чек с записью queue_numbers (см. QueueNumber). */
        queue: QueueNumber? = null,
        /** Выбранный ваучер; пишется только при живом резерве — как и остальные льготы. */
        voucherId: Int? = null,
    ): JSONObject {
        val c = scValue(info)
        // Льготы существуют только вместе с резервом: без `op` backend всё равно применит 0,
        // поэтому не пишем в чек неисполнимые free/cb (иначе они врут и в логах, и в разборе).
        val reserved = info.reservationId != null
        // Ваучер гасит backend только по QR-токену с резервом; по числовому коду ключа быть не должно.
        val voucherOk = reserved && kindOf(c) == "token"
        val o = JSONObject()
            .put("v", 2)
            .put("c", c)
            .put("ts", System.currentTimeMillis())
            .put("kind", kindOf(c))
        if (reserved) o.put("op", info.reservationId)
        if (voucherOk && voucherId != null && voucherId > 0) o.put("voucherId", voucherId)
        // sc.q — диагностика sequence: пишем только если сервер реально его прислал.
        info.seq?.takeIf { it > 0 }?.let { o.put("q", it) }
        o.put("free", if (reserved && freeApplied) 1 else 0)
            .put("cb", if (reserved) cashbackKopecks.coerceAtLeast(0L) else 0L)
        // ord — канонический ключ связи с queue_numbers; qn — только для логов.
        // Пишем оба лишь когда номер реально получен от backend до открытия чека.
        if (QueueNumber.isUsable(queue)) {
            o.put("ord", queue!!.orderId).put("qn", queue.publicNumber)
        }
        return o
    }

    /**
     * Что уходит в `extras.sc.c`.
     *
     * QR: подписанный серверный токен (то, что гость показал). Backend проверяет подпись и только
     * по нему вызывает `consumeReservation`; подмена на числовой код отключает списание — скидка
     * на кассе выдаётся, а `free_used`/`cashback_balance` не уменьшаются.
     * Ручной ввод: канонический цифровой код карты из ответа resolve (без пробелов и ведущих нулей).
     */
    internal fun scValue(info: CardInfo): String =
        if (kindOf(info.code) == "token") info.code.trim()
        else info.cardCode.ifBlank { info.code }.trim()

    /** Подписанный QR содержит точку (`<payload>.<sig>`), короткий код карты — только цифры. */
    fun kindOf(code: String): String = if (code.contains('.')) "token" else "code"

}
