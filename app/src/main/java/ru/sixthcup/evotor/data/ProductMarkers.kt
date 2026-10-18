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
 *  - `#group=cappuccino` — варианты одного напитка (размеры) собираются в одну плитку;
 *  - `#size=350`         — объём варианта в мл (сортировка и подпись «350 мл»);
 *  - `#default`          — вариант, который показывается на плитке и выбран первым;
 *  - `#opt=milk`         — товар-опция: `milk` | `syrup` | `sugar` | `extra`.
 *  - `#optlabel=<текст>` — подпись опции, если она отличается от name;
 *  - `#mods` / `#nomods` — есть ли у позиции выбор опций (объём/молоко/сироп) — по умолчанию
 *    считается по правилу «напиток» из [FreeCupRules];
 *  - `#drink=<uuid>` и `#food=<uuid>` — состав комбо; цена набора = PRICE_OUT товара-определения.
 *
 * Цена любой опции — PRICE_OUT товара из Inventory, никаких дельт на клиенте:
 * иначе в чек уйдёт цена, которой нет в номенклатуре, и склад модификаторов не спишется.
 *
 * Чистый Kotlin без Android API — покрыт юнит-тестами.
 */
object ProductMarkers {
    private val OLD = Regex("#old\\s*[=:]\\s*(\\d+(?:[.,]\\d{1,2})?)", RegexOption.IGNORE_CASE)
    private val GROUP = Regex("#group\\s*[=:]\\s*([\\p{L}\\p{N}_-]+)", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("#size\\s*[=:]\\s*(\\d{2,4})", RegexOption.IGNORE_CASE)
    private val OPT = Regex("#opt\\s*[=:]\\s*([\\p{L}\\p{N}_-]+)", RegexOption.IGNORE_CASE)
    private val OPT_LABEL = Regex("#optlabel\\s*[=:]\\s*([^#]+)", RegexOption.IGNORE_CASE)
    private val DEFAULT_FLAG = Regex("#default(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    private val MODS_ON = Regex("#mods(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    private val MODS_OFF = Regex("#nomods(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    private val REF = Regex("#(drink|food)\\s*[=:]\\s*([\\p{L}\\p{N}_-]+)", RegexOption.IGNORE_CASE)

    private val ANY = Regex(
        "#(?:" +
            "focus|combo|nocup|cup|default|mods|nomods" +
            "|group\\s*[=:]\\s*[\\p{L}\\p{N}_-]+" +
            "|size\\s*[=:]\\s*\\d{2,4}" +
            "|opt\\s*[=:]\\s*[\\p{L}\\p{N}_-]+" +
            "|optlabel\\s*[=:]\\s*[^#]+" +
            "|(?:drink|food)\\s*[=:]\\s*[\\p{L}\\p{N}_-]+" +
            "|old\\s*[=:]\\s*\\d+(?:[.,]\\d{1,2})?" +
            ")(?![\\p{L}\\p{N}_])",
        RegexOption.IGNORE_CASE,
    )

    fun isFocus(description: String?): Boolean = description.orEmpty().contains("#focus", ignoreCase = true)

    fun isCombo(name: String, description: String?): Boolean =
        description.orEmpty().contains("#combo", ignoreCase = true) || name.contains(" + ")

    /** Старая цена из `#old=…`; null, если метки нет или значение не число. Сравнение с ценой — на стороне UI. */
    fun oldPriceRub(description: String?): BigDecimal? {
        val m = OLD.find(description.orEmpty()) ?: return null
        return try { BigDecimal(m.groupValues[1].replace(',', '.')) } catch (_: Throwable) { null }
    }

    /** Ключ группы вариантов (`#group=cappuccino`) или null. */
    fun variantGroup(description: String?): String? =
        GROUP.find(description.orEmpty())?.groupValues?.get(1)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Объём из `#size=350` или null. */
    fun sizeMl(description: String?): Int? =
        SIZE.find(description.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** Группа опции: milk | syrup | sugar | extra (или произвольный ключ из админки). */
    fun optGroup(description: String?): String? =
        OPT.find(description.orEmpty())?.groupValues?.get(1)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Состав комбо: `uuid` напитка и `uuid` выпечки из `#drink=` / `#food=`. */
    fun comboRefs(description: String?): Pair<String, String>? {
        val d = description ?: return null
        var drink: String? = null
        var food: String? = null
        for (m in REF.findAll(d)) {
            when (m.groupValues[1].lowercase()) {
                "drink" -> drink = m.groupValues[2]
                "food" -> food = m.groupValues[2]
            }
        }
        return if (drink != null && food != null) drink to food else null
    }

    /** Явная настройка «есть опции» (`#mods` → true, `#nomods` → false) или null. */
    fun modsFlag(description: String?): Boolean? {
        val d = description ?: return null
        if (MODS_OFF.containsMatchIn(d)) return false
        if (MODS_ON.containsMatchIn(d)) return true
        return null
    }

    /** Явно выбранный вариант по умолчанию (`#default`). */
    fun isDefaultVariant(description: String?): Boolean = DEFAULT_FLAG.containsMatchIn(description.orEmpty())

    /** Подпись опции из `#optlabel=…` (например «Овсяное» для товара «Молоко овсяное 1 л»). */
    fun optLabel(description: String?): String? =
        OPT_LABEL.find(description.orEmpty())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Описание/рецепт без служебных меток; пустой результат → null. */
    fun cleanDescription(description: String?): String? {
        val s = description ?: return null
        return s.replace(ANY, " ").replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }
}
