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
    /** Баланс бонусов из customers/resolve; для QR обычно совпадает с cashbackKopecks. */
    val balanceKopecks: Long = cashbackKopecks,
    val reservationId: String?,
    val reservationExpiresAt: Long,
    /** "RESERVED" | "ALREADY_RESERVED" | "NONE" | "OFFLINE" | "ERROR" */
    val freeStatus: String,
    /** Серверное право списания в этом чеке. false — подарок/кэшбэк/ваучер только показываем. */
    val canRedeem: Boolean = false,
    /** `token` | `code` из resolve; null у старого кэша — тогда fallback по форме кода. */
    val kind: String? = null,
    /** `PHONE` | `QR` | `CODE` — чем гость идентифицирован. */
    val identifiedBy: String = "QR",
    /** Процент начисления, который показывает сервер. Бариста его не меняет. */
    val effectiveEarnPercent: Int? = null,
    val phoneMasked: String? = null,
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
            .put("resExp", info.reservationExpiresAt).put("status", info.freeStatus)
            .put("canRedeem", info.canRedeem).put("kind", info.kind ?: JSONObject.NULL)
            .put("balance", info.balanceKopecks)
            .put("earnPercent", info.effectiveEarnPercent ?: JSONObject.NULL)
            .put("phoneMasked", info.phoneMasked ?: JSONObject.NULL)
            .put("identifiedBy", info.identifiedBy)
            .put("note", info.note ?: JSONObject.NULL)
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
        if (System.currentTimeMillis() - p.getLong(KEY_AT, 0L) > TTL_MS) {
            when (CardTtlPolicy.action(Cart.isEmpty(), PendingPayment.active(ctx))) {
                CardTtlPolicy.Action.CLEAR -> { clear(ctx); return null }
                CardTtlPolicy.Action.KEEP_STALE -> markStale(ctx, stale = true)
            }
        }
        return try {
            val o = JSONObject(s)
            val cb = o.optLong("cb")
            CardInfo(
                code = o.getString("code"), cardCode = o.optString("cardCode"), paidCups = o.optInt("paidCups"),
                cupsForFree = o.optInt("cupsForFree", 5), freeAvailable = o.optInt("freeAvailable"),
                cashbackKopecks = cb,
                balanceKopecks = if (o.has("balance") && !o.isNull("balance")) o.optLong("balance", cb) else cb,
                reservationId = if (o.isNull("res")) null else o.getString("res"),
                reservationExpiresAt = o.optLong("resExp"), freeStatus = o.optString("status", "NONE"),
                // Старый кэш без поля: безопасный default false, до повторного resolve.
                canRedeem = o.optBoolean("canRedeem", false),
                kind = if (o.isNull("kind")) null else o.optString("kind").takeIf { it == "token" || it == "code" },
                identifiedBy = o.optString("identifiedBy").ifBlank {
                    if (o.optString("kind") == "token" || o.optString("code").contains('.')) "QR" else "CODE"
                },
                effectiveEarnPercent = if (o.has("earnPercent") && !o.isNull("earnPercent")) o.optInt("earnPercent") else null,
                phoneMasked = if (o.isNull("phoneMasked")) null else o.optString("phoneMasked").takeIf { it.isNotBlank() },
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
        /** `null` — карты нет: в чек уходят только `preorder`/`qn` (льгота заказа). */
        info: CardInfo?,
        freeApplied: Boolean,
        cashbackKopecks: Long,
        queue: QueueNumber? = null,
        voucherId: Int? = null,
        /** Предзаказ, подтянутый в этот чек (см. [PreorderPullSession]). */
        preorderId: String? = null,
    ): JSONObject {
        val sc = sc(info, freeApplied, cashbackKopecks, queue, voucherId, preorderId)
        // Плоский ключ нужен текущему парсеру: если appId не совпал с EVOTOR_APP_ID на backend,
        // льгота не потеряется. Оба значения одинаковые — двойного списания нет.
        return JSONObject()
            .put(BuildConfig.APP_UUID, JSONObject().put("sc", sc))
            .put("sc", sc)
    }

    /** extras.sc written into the receipt. The backend re-checks everything when it reads the SELL. */
    fun sc(
        info: CardInfo?,
        freeApplied: Boolean,
        cashbackKopecks: Long,
        /** Публичный номер заказа: связывает чек с записью queue_numbers (см. QueueNumber). */
        queue: QueueNumber? = null,
        /** Выбранный ваучер; пишется только при живом резерве — как и остальные льготы. */
        voucherId: Int? = null,
        /**
         * Предзаказ, состав которого подтянут в этот чек. Пишется отдельным ключом `preorder`:
         * `ord` уже занят связкой с публичным номером очереди (`bindQueueNumber` на backend),
         * перезапись сломала бы привязку номера к чеку.
         */
        preorderId: String? = null,
    ): JSONObject {
        val o = JSONObject()
            .put("v", 2)
            .put("ts", System.currentTimeMillis())
        // Карта есть — пишем карточные поля. Нет карты (льгота предзаказа) — только связь с заказом.
        val card = info?.takeIf { it.code.isNotEmpty() }
        if (card != null) {
            val c = scValue(card)
            // Льготы существуют только вместе с резервом: без `op` backend всё равно применит 0,
            // поэтому не пишем в чек неисполнимые free/cb (иначе они врут и в логах, и в разборе).
            val reserved = card.reservationId != null
            // Free gift требует hold; кэшбэк — только серверное право canRedeem (bonus-карта может быть без hold).
            val freeRedeem = reserved && card.canRedeem
            val cashbackRedeem = cashbackRedeemable(card, reserved)
            // Ваучер гасит backend только по QR-токену с резервом; по числовому коду ключа быть не должно.
            val voucherOk = freeRedeem && effectiveKind(card) == "token"
            o.put("c", c).put("kind", effectiveKind(card))
            if (reserved) o.put("op", card.reservationId)
            if (voucherOk && voucherId != null && voucherId > 0) o.put("voucherId", voucherId)
            // sc.q — диагностика sequence: пишем только если сервер реально его прислал.
            card.seq?.takeIf { it > 0 }?.let { o.put("q", it) }
            o.put("free", if (freeRedeem && freeApplied) 1 else 0)
                .put("cb", if (cashbackRedeem) cashbackKopecks.coerceAtLeast(0L) else 0L)
        }
        // ord — канонический ключ связи с queue_numbers; qn — только для логов.
        // Пишем оба лишь когда номер реально получен от backend до открытия чека.
        if (QueueNumber.isUsable(queue)) {
            o.put("ord", queue!!.orderId).put("qn", queue.publicNumber)
        }
        preorderId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }?.let { o.put("preorder", it) }
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
        if (effectiveKind(info) == "token") info.code.trim()
        else info.cardCode.ifBlank { info.code }.trim()

    /**
     * Кэшбэк: QR/код требует hold (reserved), phone-bonus — только серверный canRedeem.
     * Так бонусная карта может списываться без reservationId, не открывая free-gift.
     */
    fun cashbackRedeemable(info: CardInfo, reserved: Boolean): Boolean =
        info.canRedeem && (reserved || info.identifiedBy.equals("PHONE", ignoreCase = true))

    /** Серверный kind, иначе — форма кода: подписанный QR содержит точку, короткий код — только цифры. */
    fun effectiveKind(info: CardInfo): String =
        info.kind?.takeIf { it == "token" || it == "code" } ?: kindOf(info.code)

    fun kindOf(code: String): String = if (code.contains('.')) "token" else "code"

}
