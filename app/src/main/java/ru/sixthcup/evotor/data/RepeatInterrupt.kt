package ru.sixthcup.evotor.data

/**
 * Когда касса имеет право спросить «Повтор не завершён?».
 *
 * Диалог не показывается, если кассир уже открыл другую карточку ([sheetOpen]) или он уже
 * на экране ([dialogShowing]) — иначе два листа накладываются друг на друга, а обработчик
 * закрытия срабатывает повторно.
 */
object RepeatInterrupt {

    fun shouldShow(pending: Int?, sheetOpen: Boolean, dialogShowing: Boolean): Boolean =
        pending != null && !sheetOpen && !dialogShowing

    /** Текст уведомления при прерывании: сколько позиций не добавлено. */
    fun notice(left: Int, productName: String): String =
        if (left > 0) "Повтор не завершён: осталось $left поз."
        else "Повтор не завершён: $productName не добавлен"
}
