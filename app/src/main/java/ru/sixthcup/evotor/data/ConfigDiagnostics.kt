package ru.sixthcup.evotor.data

/**
 * Почему в окне товара нет молока/добавок — текстом для кассира и владельца.
 *
 * На терминале нельзя посмотреть `logcat`, поэтому причина «схема не пришла» должна быть
 * видна на экране: нет `productId`, сервер не отдаёт маршрут (404), нет сети,
 * схема не назначена объёму или опции недоступны.
 */
object ConfigDiagnostics {

    /** Что известно про последнюю попытку получить конфигурацию этого товара. */
    data class Fetch(
        val productId: Int?,
        val storeUuid: String?,
        /** Запрос действительно уходил на сервер. */
        val requested: Boolean,
        val httpStatus: Int,
        /** 404 — маршрут конфигурации не реализован на backend. */
        val unsupported: Boolean,
        val error: String?,
        /** Ответ взят из кэша (сервер недоступен). */
        val fromCache: Boolean,
    ) {
        companion object {
            val none = Fetch(null, null, requested = false, httpStatus = 0, unsupported = false, error = null, fromCache = false)
        }
    }

    /** Что реально попало в окно. */
    data class View(
        val milkOptions: Int,
        val additionOptions: Int,
        /** Топпинги карточки из каталога (`toppings[]` / `#opt=`). */
        val catalogToppings: Int,
        /** Объём карточки найден среди объёмов конфигурации. */
        val volumeMatched: Boolean,
        /** В конфигурации вообще есть схемы (молоко или добавки). */
        val hasSchemes: Boolean,
    )

    /**
     * Подсказка под блоком объёма. `null` — всё на месте, ничего писать не нужно.
     */
    fun hint(fetch: Fetch, view: View): String? {
        if (view.milkOptions + view.additionOptions > 0) return null
        if (view.catalogToppings > 0) return null

        val productId = fetch.productId
        return when {
            productId == null || productId <= 0 ->
                "Схема не запрошена: товар не сопоставлен с сервером (нет productId). Обновите каталог."
            fetch.storeUuid.isNullOrBlank() ->
                "Схема не запрошена: не определён UUID точки."
            !fetch.requested ->
                "Схема не запрошена."
            fetch.unsupported ->
                "Сервер не отдаёт конфигурацию товара (HTTP 404) — окно работает по каталогу."
            fetch.fromCache ->
                "Схема не назначена (используются сохранённые данные с сервера)."
            fetch.error != null ->
                "Схема не получена: сервер недоступен. Проверьте связь и повторите."
            view.hasSchemes ->
                if (view.volumeMatched) "Схема есть, но опции недоступны (нет товаров Эвотор)."
                else "Для этого объёма схема не назначена (объём не сопоставлен с сервером)."
            else ->
                "Молоко и добавки не назначены: у товара нет схемы на сервере."
        }
    }
}
