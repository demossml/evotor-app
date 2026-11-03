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
        /** `configErrors` от backend: `links` и подобные коды. */
        val configErrors: List<String> = emptyList(),
        /** uuid товара терминала, для которого открыт лист. */
        val terminalUuid: String? = null,
        /** `productId` объёма, сопоставленного с товаром терминала (`null` — не сопоставлен). */
        val volumeProductId: Int? = null,
        /** Объёмы из ответа конфигурации: `productId:evotorUuid`. */
        val volumeUuids: List<String> = emptyList(),
    )

    /**
     * Коды ошибок backend → фраза для кассира. Неизвестный код/сетевую ошибку
     * возвращаем как `null`, чтобы вызывающий показал текст по умолчанию.
     */
    fun errorText(code: String?): String? = when (code?.trim()?.lowercase()) {
        "product_not_found" -> "Товар не найден на сервере — обновите каталог"
        "store_required" -> "Сервер не получил UUID точки — проверьте настройку кассы"
        "invalid_product_id" -> "У товара нет корректного id на сервере — обновите каталог"
        "proxy_forbidden" -> "Нет доступа к схемам товара (403) — проверьте токен точки"
        "store_uuid_mismatch" -> "Точка в запросе и в заголовке не совпадают"
        "links" -> "объёмы не привязаны к товарам Эвотор этой точки"
        else -> null
    }

    /** Почему объём/опция недоступны — по `notReadyReason` сервера (admin «Готовность кассы» показывает те же коды). */
    fun unavailableText(reason: String?): String = when (reason?.trim()?.lowercase()) {
        "no_store_link" -> "Объём не выгружен в Эвотор на этой точке — продажа запрещена"
        "no_evotor_uuid" -> "У объёма нет UUID Эвотора — выгрузите товар заново"
        "zero_price" -> "У объёма цена 0 ₽ — продать нельзя"
        else -> "Этот объём недоступен — выберите другой размер"
    }

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
                errorText(fetch.error) ?: "Схема не получена: сервер недоступен. Проверьте связь и повторите."
            view.configErrors.isNotEmpty() ->
                "Сервер вернул конфигурацию с ошибкой: " + errorText(view.configErrors.first())
                    .orEmpty().ifBlank { view.configErrors.joinToString(", ") }
            view.hasSchemes && !view.volumeMatched && view.volumeProductId == null ->
                // Самый частый случай: схемы есть, но объём ответа не совпал с товаром терминала.
                "Для этого объёма схема не назначена (объём не сопоставлен с сервером): " +
                    "товар ${view.terminalUuid ?: "-"}, в конфигурации " +
                    view.volumeUuids.joinToString(", ").ifBlank { "объёмов нет" }
            view.hasSchemes ->
                "Схема есть, но опции недоступны (нет товаров Эвотор), объём pid=${view.volumeProductId}."
            else ->
                "Молоко и добавки не назначены: у товара нет схемы на сервере."
        }
    }
}
