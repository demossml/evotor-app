package ru.sixthcup.evotor.net

/**
 * Единые правила «можно ли идти в device-API» и «что делать с ответом авторизации».
 *
 * APK не хранит секрет прокси Эвотора: `Authorization` добавляет Cloud кабинета.
 * Поэтому на стороне кассы мы можем гарантировать только два инварианта:
 *  1. запрос без UUID точки не уходит (иначе backend не знает, к какой точке он относится);
 *  2. 401/403 и прочие HTTP-ошибки не превращаются в «офлайн-карту» с полуоткрытыми бонусами.
 *
 * Чистые функции — чтобы правила были покрыты unit-тестами без Android.
 */
object DeviceAuthPolicy {
    const val MISSING_STORE = "missing_store"

    /** UUID точки обязателен для всех device-запросов, кроме catalog без storeUuid (legacy). */
    fun blockedError(storeUuid: String?): String? =
        if (storeUuid.isNullOrBlank()) MISSING_STORE else null

    /** 401/403 — это вопрос привязки/доступа, а не «нет связи». */
    fun authFailure(status: Int): Boolean = status == 401 || status == 403

    /**
     * Любой HTTP-ответ с ошибкой означает, что сервер карту не подтвердил.
     * Подписанный QR оставляем только при сетевом исключении/таймауте (см. LoyaltyApi),
     * а не при 4xx/5xx — иначе карта выглядит «офлайн-рабочей» без проверки.
     */
    fun dropCard(status: Int): Boolean = status >= 400

    fun missingStoreMessage(): String =
        "Не определена точка продаж. Перезапустите кассу или перепривяжите её в кабинете Эвотор."

    fun authMessage(status: Int): String =
        "Терминал не авторизован ($status). Проверьте привязку кассы в кабинете Эвотор."
}
