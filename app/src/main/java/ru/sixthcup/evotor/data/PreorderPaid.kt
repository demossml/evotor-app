package ru.sixthcup.evotor.data

/**
 * Политика «сообщить backend, что предзаказ оплачен» — чистая логика для тестов.
 *
 * Правила (аудит B-08 / P0-6):
 * 1. `paid` отправляем только когда POS **напечатал** чек продажи: аннулированный чек печати не даёт,
 *    значит ложного `paid` не будет.
 * 2. Сетевой сбой и 5xx повторяем ограниченное число раз; 4xx (409/401/403/404) не повторяем.
 * 3. Повтор безопасен: backend делает `mark-paid` идемпотентным.
 */
object PreorderPaid {
    const val MAX_ATTEMPTS = 3

    /** Пауза перед повтором; последняя попытка не ждёт. */
    fun delayMs(attempt: Int): Long = when (attempt) {
        1 -> 1_500L
        2 -> 4_000L
        else -> 0L
    }

    fun retryable(error: String?): Boolean {
        val e = error?.trim()?.lowercase().orEmpty()
        if (e.isEmpty()) return false
        if (e == "network" || e == "timeout" || e == "io") return true
        val http = Regex("^http_(\\d{3})$").find(e)?.groupValues?.get(1)?.toIntOrNull()
        if (http != null) return http >= 500 || http == 408 || http == 429
        return e == "internal_error" || e == "server_error" || e == "bad_gateway"
    }

    /** `mark-paid` допустим только для распечатанного чека и известного id заказа. */
    fun shouldMarkSalePaid(salePrinted: Boolean, preorderId: String?): Boolean =
        salePrinted && !preorderId.isNullOrBlank()
}
