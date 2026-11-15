package ru.sixthcup.evotor.data

/**
 * Единый допуск на истечение hold-резерва для UI и фискального расчёта.
 *
 * Раньше `CardProgress` жил с 120 секундами, а `DiscountCalc` — с 300: экран мог
 * показывать «подарок недоступен», пока чек ещё применял скидку (или наоборот).
 * Менять значение разрешено только здесь.
 */
object ReservationPolicy {
    /** Секунды после `reservationExpiresAt`, когда hold ещё считается живым. */
    const val GRACE_SEC = 120L
}
