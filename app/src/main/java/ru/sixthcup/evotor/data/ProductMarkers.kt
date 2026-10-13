package ru.sixthcup.evotor.data

import java.math.BigDecimal

/**
 * Метки в описании товара Эвотор (поле DESCRIPTION), которыми администратор управляет витриной кассы
 * без изменения backend:
 *  - `#focus`      — показать в карусели «Фокус»;
 *  - `#combo`      — комбо (тоже попадает в «Фокус»); комбо также распознаётся по « + » в названии;
 *  - `#old=250`    — «старая цена» в рублях: на кассе показывается зачёркнутой рядом с ценой PRICE_OUT.
 *                    Это только подпись. В чек всегда уходит PRICE_OUT, скидок приложение не считает.
 *  - `#cup`/`#nocup` — см. FreeCupRules (здесь только вырезаются из видимого текста).
 * Чистый Kotlin без Android API — покрыт юнит-тестами.
 */
object ProductMarkers {
    private val OLD = Regex("#old\\s*[=:]\\s*(\\d+(?:[.,]\\d{1,2})?)", RegexOption.IGNORE_CASE)
    private val ANY = Regex("#(?:focus|combo|nocup|cup|old\\s*[=:]\\s*\\d+(?:[.,]\\d{1,2})?)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)

    fun isFocus(description: String?): Boolean = description.orEmpty().contains("#focus", ignoreCase = true)

    fun isCombo(name: String, description: String?): Boolean =
        description.orEmpty().contains("#combo", ignoreCase = true) || name.contains(" + ")

    /** Старая цена из `#old=…`; null, если метки нет или значение не число. Сравнение с ценой — на стороне UI. */
    fun oldPriceRub(description: String?): BigDecimal? {
        val m = OLD.find(description.orEmpty()) ?: return null
        return try { BigDecimal(m.groupValues[1].replace(',', '.')) } catch (_: Throwable) { null }
    }

    /** Описание/рецепт без служебных меток; пустой результат → null. */
    fun cleanDescription(description: String?): String? {
        val s = description ?: return null
        return s.replace(ANY, " ").replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }
}
