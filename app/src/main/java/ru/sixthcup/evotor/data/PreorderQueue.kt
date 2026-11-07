package ru.sixthcup.evotor.data

import ru.sixthcup.evotor.net.OrderApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Очередь предзаказов: подписи статусов, доступные кнопки, ETA и тексты ошибок.
 *
 * Чистые функции — проверяются unit-тестами; экраны только рисуют результат.
 * Контракт статусов: `draft → accepted → cooking → ready → paid` (прыжки запрещает backend).
 */
object PreorderQueue {

    const val DRAFT = "draft"
    const val ACCEPTED = "accepted"
    const val COOKING = "cooking"
    const val READY = "ready"
    const val PAID = "paid"
    const val REJECTED = "rejected"
    const val EXPIRED = "expired"

    /** Подпись статуса для списка и панели. */
    fun statusLabel(status: String?): String = when (status?.trim()?.lowercase()) {
        DRAFT -> "Новый"
        ACCEPTED -> "Принят"
        COOKING -> "Готовится"
        READY -> "Готов"
        PAID -> "Оплачен"
        REJECTED -> "Отклонён"
        EXPIRED -> "Истёк"
        null, "" -> "—"
        else -> status!!.trim()
    }

    enum class Action { ACCEPT, COOKING, READY, PULL, REJECT, CLOSE }

    /** Кнопки по статусу (таблица 2.3 промпта). */
    fun actions(status: String?): List<Action> = when (status?.trim()?.lowercase()) {
        DRAFT -> listOf(Action.ACCEPT, Action.REJECT)
        ACCEPTED -> listOf(Action.COOKING, Action.READY, Action.PULL, Action.REJECT)
        COOKING -> listOf(Action.READY, Action.PULL, Action.REJECT)
        READY -> listOf(Action.PULL, Action.REJECT)
        PAID, REJECTED, EXPIRED -> listOf(Action.CLOSE)
        else -> listOf(Action.CLOSE)
    }

    fun actionLabel(action: Action): String = when (action) {
        Action.ACCEPT -> "Принять"
        Action.COOKING -> "Готовится"
        Action.READY -> "Готов"
        Action.PULL -> "В чек"
        Action.REJECT -> "Отклонить"
        Action.CLOSE -> "Закрыть"
    }

    /** Следующий статус для основного перехода; `null` — переходов нет. */
    fun nextStatus(status: String?): String? = when (status?.trim()?.lowercase()) {
        DRAFT -> ACCEPTED
        ACCEPTED -> COOKING
        COOKING -> READY
        else -> null
    }

    fun canPull(status: String?): Boolean = Action.PULL in actions(status)

    /** Порядок в очереди: сначала новые (их ждут), потом по времени создания. */
    fun sortForQueue(rows: List<OrderApi.ActiveRow>): List<OrderApi.ActiveRow> {
        fun rank(s: String) = when (s.trim().lowercase()) {
            DRAFT -> 0
            ACCEPTED -> 1
            COOKING -> 2
            READY -> 3
            else -> 4
        }
        return rows.sortedWith(compareBy({ rank(it.status) }, { it.createdAt }))
    }

    /**
     * ISO-время сервера → epoch ms; `null`, если формат не разобрать.
     *
     * Смещение зоны разбираем сами: шаблон `XXX` требует API 24, а minSdk проекта — 23.
     */
    fun parseIso(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        var v = raw.trim()
        var offsetMin = 0
        if (v.endsWith("Z")) v = v.dropLast(1)
        Regex("([+-])(\\d{2}):(\\d{2})$").find(v)?.let { m ->
            val sign = if (m.groupValues[1] == "-") -1 else 1
            offsetMin = sign * (m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt())
            v = v.substring(0, m.range.first)
        }
        val pattern = if (v.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSS" else "yyyy-MM-dd'T'HH:mm:ss"
        return try {
            val f = SimpleDateFormat(pattern, Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            f.parse(v)?.time?.minus(offsetMin * 60_000L)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Осталось до готовности. `null` — ETA неизвестен («уточняем»), `0` — уже готов или просрочен.
     * Считаем от `serverNow`, а не от локальных часов: на терминале время может плыть.
     */
    fun etaMinutes(etaReadyAt: String?, serverNow: String?, nowMs: Long = System.currentTimeMillis()): Int? {
        val eta = parseIso(etaReadyAt) ?: return null
        val base = parseIso(serverNow) ?: nowMs
        val left = eta - base
        if (left <= 0) return 0
        return ((left + 59_999) / 60_000).toInt()
    }

    /** Строка ETA для карточки и баннера. */
    fun etaText(status: String?, etaReadyAt: String?, serverNow: String?, nowMs: Long = System.currentTimeMillis()): String {
        if (status?.trim()?.lowercase() == READY) return "Готов"
        val min = etaMinutes(etaReadyAt, serverNow, nowMs)
            ?: return if (status?.trim()?.lowercase() == PAID) "" else "уточняем"
        return if (min <= 0) "уточняем" else "~$min мин"
    }

    /** Баннер на главном экране: `null` — показывать нечего. */
    fun bannerText(rows: List<OrderApi.ActiveRow>, nowMs: Long = System.currentTimeMillis()): String? {
        if (rows.isEmpty()) return null
        val sorted = sortForQueue(rows)
        val first = sorted.first()
        val parts = ArrayList<String>(4)
        parts.add("Заказ ${first.shortCode}")
        if (first.itemsCount > 0) parts.add("${first.itemsCount} поз.")
        val eta = etaText(first.status, first.etaReadyAt, first.serverNow, nowMs)
        if (eta.isNotBlank()) parts.add(eta) else parts.add(statusLabel(first.status))
        val head = parts.joinToString(" · ")
        return if (sorted.size > 1) "$head · ещё ${sorted.size - 1}" else head
    }

    /** Тексты ошибок по коду backend (матрица 3). */
    fun errorText(code: String?, httpStatus: Int = 0): String = when (code?.trim()?.lowercase()) {
        "unauthorized" -> "Терминал не авторизован. Перепривяжите кассу"
        "wrong_store" -> "Заказ оформлен для другой кофейни"
        "not_found" -> "Заказ не найден"
        "expired" -> "Срок заказа истёк"
        "already_paid" -> "Заказ уже оплачен"
        "rejected" -> "Заказ отклонён или отменён"
        "invalid_transition" -> "Статус уже изменился — обновите заказ"
        "benefit_conflict" -> "Бонус гостя уже применён в другом чеке"
        "bad_request" -> "Некорректный запрос"
        "network" -> "Нет связи с сервером"
        else -> when (httpStatus) {
            401 -> "Терминал не авторизован. Перепривяжите кассу"
            403 -> "Заказ оформлен для другой кофейни"
            404 -> "Заказ не найден"
            410 -> "Срок заказа истёк"
            in 500..599 -> "Ошибка сервера ($httpStatus)"
            0 -> "Нет связи с сервером"
            else -> "Не удалось получить заказ ($httpStatus)"
        }
    }

    /** Сколько минут осталось до истечения заказа (для предупреждения кассиру). */
    fun expiresInMinutes(expiresAt: String?, serverNow: String?, nowMs: Long = System.currentTimeMillis()): Int? {
        val exp = parseIso(expiresAt) ?: return null
        val base = parseIso(serverNow) ?: nowMs
        val left = exp - base
        if (left <= 0) return 0
        return (left / 60_000).toInt()
    }

    /** Подпись вида бонуса, если backend не прислал заголовок. */
    fun benefitKindLabel(kind: String?): String = when (kind?.trim()?.lowercase()) {
        "gift" -> "Подарок"
        "voucher" -> "Ваучер"
        "cashback" -> "Кэшбэк"
        else -> "Бонус"
    }

    /**
     * Деньги заказа для панели: оценка гостя, бонус и итог.
     *
     * Бонус рисуем только когда backend прислал и `benefit`, и `quoteTotal`: без итога строка
     * «−300 ₽» повисла бы без пары, а APK сам скидку не считает (правило 1.7.1).
     */
    data class Money(
        val estimateRub: Int,
        val benefitTitle: String? = null,
        val benefitRub: Int = 0,
        val totalRub: Int,
    ) {
        val hasBenefit: Boolean get() = benefitTitle != null

        /** Строка панели: `Бонус: <title> −300 ₽`. */
        val benefitLine: String?
            get() = benefitTitle?.let { if (benefitRub > 0) "Бонус: $it −$benefitRub ₽" else "Бонус: $it" }

        /** Компактный итог: `Оценка гостя: 650 ₽ · бонус «Подарок» −300 ₽ · итог 350 ₽`. */
        val summary: String
            get() = buildString {
                append("Оценка гостя: $estimateRub ₽")
                if (benefitTitle != null) {
                    append(" · бонус «$benefitTitle»")
                    if (benefitRub > 0) append(" −$benefitRub ₽")
                    append(" · итог $totalRub ₽")
                }
            }
    }

    fun money(
        estimateTotal: Int,
        benefit: OrderApi.Benefit?,
        benefitTotal: Int? = 0,
        quoteTotal: Int? = null,
    ): Money {
        if (benefit == null || quoteTotal == null) return Money(estimateTotal, totalRub = estimateTotal)
        val amount = if (benefit.amountRub > 0) benefit.amountRub else (benefitTotal ?: 0).coerceAtLeast(0)
        val title = benefit.title.trim().ifBlank { benefitKindLabel(benefit.kind) }
        return Money(estimateTotal, title, amount, quoteTotal)
    }

    /** Итог без разбора бонуса — нужен списку заказов. */
    fun totalRub(estimateTotal: Int, quoteTotal: Int?): Int = quoteTotal ?: estimateTotal
}
