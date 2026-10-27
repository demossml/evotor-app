package ru.sixthcup.evotor.data

/** Чем закончился перезапрос карты на сервере. */
enum class RefreshOutcome {
    /** Данные обновились — сессия заменена. */
    UPDATED,

    /** Сервер ответил, что карты нет: сессию убираем. */
    DROPPED,

    /** Сеть/сервер недоступны: прежние данные остаются, помечаются устаревшими. */
    KEPT_STALE,

    /** Карты в сессии нет — обновлять нечего. */
    NO_CARD,
}

data class RefreshResult(val outcome: RefreshOutcome, val info: CardInfo?) {
    val updated: Boolean get() = outcome == RefreshOutcome.UPDATED
    val dropped: Boolean get() = outcome == RefreshOutcome.DROPPED
}

/**
 * Логика перезапроса карты, без Android и сети: чем запрашивать и что делать с ответом.
 * Сам запрос и запись в SharedPreferences — [CardSession.refreshFromServer].
 */
object CardRefresh {

    /**
     * Credential для повторного `resolve`: подписанный токен, если карта была считана по QR
     * (тогда можно и списать), иначе канонический цифровой код.
     */
    fun credential(info: CardInfo): String? {
        val code = info.code.trim()
        if (code.isEmpty()) return null
        return if (CardSession.kindOf(code) == "token") code
        else info.cardCode.ifBlank { code }.trim().ifEmpty { null }
    }

    fun apply(current: CardInfo, fetched: CardInfo): RefreshResult = when {
        fetched.code.isNotEmpty() -> RefreshResult(RefreshOutcome.UPDATED, fetched)
        // Сервер явно отказал (карта удалена/заблокирована) — держать её на чеке нельзя.
        fetched.freeStatus == "ERROR" -> RefreshResult(RefreshOutcome.DROPPED, null)
        // OFFLINE и прочие сбои: показываем прежние данные, помечаем устаревшими.
        else -> RefreshResult(RefreshOutcome.KEPT_STALE, current)
    }

    /** Есть ли смысл перерисовывать экран и показывать уведомление. */
    fun isNotable(current: CardInfo, next: CardInfo): Boolean =
        current.paidCups != next.paidCups ||
            current.paidTotal != next.paidTotal ||
            current.freeAvailable != next.freeAvailable ||
            current.cashbackKopecks != next.cashbackKopecks ||
            current.freeStatus != next.freeStatus
}
