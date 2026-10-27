package ru.sixthcup.evotor.data

/**
 * Что касса показывает гостю про прогресс и подарок — считается из ответа `resolve`.
 *
 * Ключевая разница: **QR-токен** даёт резерв, поэтому сервер сообщает, сколько подарков реально
 * можно списать; **ручной цифровой код** резерва не создаёт, и backend обнуляет `freeAvailable`
 * и `cashback` (антифрод). Поэтому прогресс для ручного кода может быть известен, а списание —
 * никогда. Модель разделяет эти два факта: [giftAvailable] (что показать) и [redeemEnabled]
 * (что можно списать чеком).
 */
data class CardProgress(
    /** Заполнено кружков в текущем цикле: 0…[goal]. */
    val cups: Int,
    val goal: Int,
    /** Подарок есть или уже заработан — можно сообщить гостю. */
    val giftAvailable: Boolean,
    /** Сервер дал достаточные данные, чтобы утверждать это (иначе — «уточните по QR»). */
    val giftKnown: Boolean,
    /** Списывать подарок в этом чеке можно только по QR с живым резервом. */
    val redeemEnabled: Boolean,
    /** Кэшбэк в копейках. */
    val cashbackKopecks: Long,
    /** Сервер сообщил сумму (для ручного кода она скрыта). */
    val cashbackKnown: Boolean,
    /** `token` | `code` — по чему гость идентифицирован. */
    val kind: String,
) {
    val left: Int get() = (goal - cups).coerceAtLeast(0)

    companion object {
        /** Допуск на «чек пробили чуть позже истечения резерва», как в [DiscountCalc]. */
        const val RESERVE_GRACE_SEC = 120L

        fun of(info: CardInfo, nowSec: Long = System.currentTimeMillis() / 1000): CardProgress {
            val goal = info.cupsForFree.coerceAtLeast(1)
            val kind = CardSession.kindOf(info.code)
            val manual = kind == "code"

            // paidTotal (если сервер его отдал) — единственный точный источник: paidCups у ручного
            // кода означает остаток цикла, а не «сколько набрано».
            val cups = info.paidTotal?.let { (it % goal).coerceIn(0, goal) }
                ?: info.paidCups.coerceIn(0, goal)
            val earned = info.paidTotal?.let { it / goal - (info.freeUsed ?: 0) }

            val giftAvailable = info.freeAvailable > 0 ||
                (earned != null && earned > 0) ||
                info.paidCups >= goal ||
                (info.paidTotal != null && info.paidTotal > 0 && cups == 0)
            val giftKnown = info.freeAvailable > 0 || earned != null || info.paidCups >= goal || !manual

            val reserveAlive = info.reservationExpiresAt == 0L || info.reservationExpiresAt + RESERVE_GRACE_SEC >= nowSec
            val redeemEnabled = !manual && info.reservationId != null && info.freeAvailable > 0 && reserveAlive

            return CardProgress(
                cups = cups,
                goal = goal,
                giftAvailable = giftAvailable,
                giftKnown = giftKnown,
                redeemEnabled = redeemEnabled,
                cashbackKopecks = info.cashbackKopecks.coerceAtLeast(0L),
                cashbackKnown = info.cashbackKopecks > 0 || !manual,
                kind = kind,
            )
        }
    }
}
