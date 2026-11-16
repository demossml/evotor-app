package ru.sixthcup.evotor.data

/**
 * Нормализация и маска телефона. На сервер уходят только цифры.
 *
 * Внутри UI храним только национальные 10 цифр (без кода страны). `+7` — это
 * оформление, а не часть набираемого номера: иначе каждая перерисовка снова
 * принимает семёрку из префикса за первую цифру и дописывает ещё одну.
 */
object PhoneUtil {

    fun digits(raw: String?): String = raw.orEmpty().filter { it.isDigit() }

    /**
     * Национальные 10 цифр без кода страны.
     *
     * Понимает и частично отформатированную строку (`+7 (999`), и полный display,
     * и сырой ввод `8XXXXXXXXXX` / `7XXXXXXXXXX`.
     */
    fun nationalDigits(raw: String?): String {
        val source = raw.orEmpty().trimStart()
        val d = digits(source)
        if (d.isEmpty()) return ""
        val countryPrefix = source.startsWith("+7") ||
            d.startsWith("8") ||
            (d.startsWith("7") && d.length <= 11)
        val national = if (countryPrefix) d.drop(1) else d
        return national.take(10)
    }

    /** Маска для поля ввода: +7 (900) 123-45-67. Неполный номер форматируется по мере ввода. */
    fun formatDisplay(national: String): String {
        val n = national.filter { it.isDigit() }.take(10)
        if (n.isEmpty()) return "+7 "
        val a = n.take(3)
        val b = n.drop(3).take(3)
        val c = n.drop(6).take(2)
        val e = n.drop(8).take(2)
        return buildString {
            append("+7")
            if (a.isNotEmpty()) {
                append(" (").append(a)
                // Закрываем код только когда уже есть следующая цифра: иначе
                // backspace на границе кода выглядит как «ничего не удалилось».
                if (a.length == 3 && b.isNotEmpty()) append(") ")
            }
            append(b)
            if (c.isNotEmpty()) append("-").append(c)
            if (e.isNotEmpty()) append("-").append(e)
        }
    }

    /** Для API: 11 цифр `7…` на полном номере, иначе — неполный national. */
    fun toApiDigits(national: String): String {
        val n = nationalDigits(national)
        return if (n.length == 10) "7$n" else n
    }

    fun isComplete(national: String): Boolean = nationalDigits(national).length == 10

    /** Совместимость: 10 цифр → 7XXXXXXXXXX; 8XXXXXXXXXX → 7XXXXXXXXXX; иначе national. */
    fun normalize(raw: String?): String {
        val n = nationalDigits(raw)
        return if (n.length == 10) "7$n" else n
    }

    fun isValid(raw: String?): Boolean = nationalDigits(raw).length == 10

    /** Совместимость со старыми вызовами; для UI используйте [formatDisplay]. */
    fun format(raw: String?): String =
        if (raw.isNullOrBlank()) "" else formatDisplay(nationalDigits(raw))

    /** Маска от сервера или локальная: +7 ••• ••• 45 67. */
    fun masked(raw: String?): String {
        val d = normalize(raw)
        if (d.length != 11) return raw.orEmpty()
        return "+7 ••• ••• ${d.substring(7, 9)} ${d.substring(9, 11)}"
    }
}
