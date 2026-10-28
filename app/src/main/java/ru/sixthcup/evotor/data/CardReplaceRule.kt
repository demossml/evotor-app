package ru.sixthcup.evotor.data

/**
 * Правило замены карты при сканировании.
 *
 * Тот же QR (или тот же цифровой код) — это повторный скан той же карты: сессию не сбрасываем
 * и вопрос «Заменить карту?» не задаём. Меняем только когда гость реально другой.
 */
object CardReplaceRule {

    /**
     * Код для сравнения: пробелы, префиксы `6.7:` / `67coffee:`, ведущие нули у цифрового кода
     * и регистр не значимы. Канонический вид совпадает с `resolve.cardCode`.
     */
    fun normalize(code: String): String {
        val cleaned = code.trim()
            .removePrefix("6.7:")
            .removePrefix("67coffee:")
            .trim()
            .lowercase()
        if (cleaned.isEmpty()) return ""
        // Молоко без цифр — QR-токен: сравниваем как есть.
        if (cleaned.any { !it.isDigit() }) return cleaned
        return cleaned.trimStart('0').ifEmpty { "0" }
    }

    fun shouldReplaceCard(current: CardInfo?, incomingCode: String): Boolean {
        if (current == null) return false
        val currentCode = normalize(current.code)
        val incoming = normalize(incomingCode)
        if (currentCode.isEmpty() || incoming.isEmpty()) return false
        return currentCode != incoming
    }
}
