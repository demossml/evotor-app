package ru.sixthcup.evotor.data

/**
 * Условия фонового обновления карты на кассе.
 *
 * Обновляем не чаще [INTERVAL_MS], только когда карта привязана и экран виден: иначе касса
 * начнёт дёргать сервер каждые пару секунд и мешать продаже.
 */
object CardPoll {
    /** Интервал опроса. Меньше 30 с смысла нет: backend видит SELL только после своего poll. */
    const val INTERVAL_MS = 30_000L

    fun shouldRefresh(hasCard: Boolean, visible: Boolean, lastAtMs: Long, nowMs: Long): Boolean {
        if (!hasCard || !visible) return false
        if (lastAtMs <= 0L) return true
        return nowMs - lastAtMs >= INTERVAL_MS
    }
}
