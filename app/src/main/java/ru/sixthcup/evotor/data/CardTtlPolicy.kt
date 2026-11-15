package ru.sixthcup.evotor.data

/**
 * Что делать с просроченной сессией карты (TTL 10 минут).
 *
 * Если кассир уже собрал корзину или чек ждёт подтверждения, карту нельзя стирать молча:
 * иначе продажа уйдёт без `c/kind` и гость не получит чашки. В этом случае оставляем
 * сессию и помечаем её stale — повторный resolve обновит данные.
 */
object CardTtlPolicy {

    enum class Action { CLEAR, KEEP_STALE }

    fun action(cartEmpty: Boolean, pendingPayment: Boolean): Action =
        if (!cartEmpty || pendingPayment) Action.KEEP_STALE else Action.CLEAR
}
