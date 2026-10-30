package ru.sixthcup.evotor.data

/**
 * Канон идентичности товара 6.7 на терминале: `sc-<productId>` (группа — `sc-g-<id>`).
 *
 * Backend пишет его в `article_number` облака Эвотор. В `ProductTable` integration-library
 * есть только `CODE`, поэтому читаем все кандидаты (ARTICLE_NUMBER/ARTICLE/CODE)
 * и берём значение формата `sc-…`; иначе — первое непустое (legacy).
 */
object ArticleCode {
    val COLUMNS = listOf("ARTICLE_NUMBER", "articleNumber", "article_number", "ARTICLE", "article", "CODE", "code")

    private val CANON = Regex("""^sc-(g-)?\d+$""", RegexOption.IGNORE_CASE)

    fun isCanon(value: String?): Boolean = value != null && CANON.matches(value.trim())

    fun pick(candidates: List<String?>): String? {
        val clean = candidates.mapNotNull { it?.trim()?.takeIf { v -> v.isNotEmpty() } }
        return clean.firstOrNull { isCanon(it) } ?: clean.firstOrNull()
    }
}
