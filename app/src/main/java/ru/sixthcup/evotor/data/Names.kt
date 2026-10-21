package ru.sixthcup.evotor.data

import java.text.Normalizer
import java.util.Locale

/**
 * Единая нормализация названий товаров для сопоставления API ↔ номенклатура Эвотор
 * и для фильтра витрины.
 *
 * Ключевой момент: объём из названия срезается («Капучино 350 мл» → «капучино»),
 * иначе товар с размером в названии никогда не совпадёт с записью API без размера,
 * а добавки продолжат висеть в общем меню отдельными плитками.
 */
object Names {

    /** «350 мл», «0,35 л», «350ml», «1 л» — вместе с ведущими разделителями. */
    private val VOLUME_SUFFIX = Regex(
        """[\s,()\[\]-]*\d+(?:[.,]\d+)?\s*(?:мл|ml|л|l)[\s,()\[\].!-]*""",
        RegexOption.IGNORE_CASE,
    )

    fun normalize(value: String?): String {
        var s = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
            .lowercase(Locale.forLanguageTag("ru-RU"))
            .replace('ё', 'е')
        s = VOLUME_SUFFIX.replace(s, " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** Совпадает ли название товара с названием добавки (с точностью до нормализации). */
    fun sameName(a: String?, b: String?): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        return na.isNotEmpty() && na == nb
    }
}
