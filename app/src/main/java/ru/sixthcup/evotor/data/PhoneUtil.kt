package ru.sixthcup.evotor.data

/** Нормализация и маска телефона. На сервер уходят только цифры. */
object PhoneUtil {

    fun digits(raw: String?): String = raw.orEmpty().filter { it.isDigit() }

    /** 10 цифр → 7XXXXXXXXXX; 8XXXXXXXXXX → 7XXXXXXXXXX; иначе как ввели. */
    fun normalize(raw: String?): String {
        val d = digits(raw)
        return when {
            d.length == 11 && d.startsWith("8") -> "7" + d.drop(1)
            d.length == 10 -> "7$d"
            else -> d
        }
    }

    fun isValid(raw: String?): Boolean {
        val n = normalize(raw)
        return n.length == 11 && n.startsWith("7")
    }

    /** Маска для поля ввода: +7 (900) 123-45-67. Неполный номер форматируется по мере ввода. */
    fun format(raw: String?): String {
        val d = digits(raw).let {
            when {
                it.length == 10 -> "7$it"
                it.length == 11 && it.startsWith("8") -> "7" + it.drop(1)
                else -> it
            }
        }
        if (d.isEmpty()) return ""
        return when {
            d.length <= 1 -> "+7"
            d.length <= 4 -> "+7 (${d.drop(1)}"
            d.length <= 7 -> "+7 (${d.substring(1, 4)}) ${d.substring(4)}"
            d.length <= 9 -> "+7 (${d.substring(1, 4)}) ${d.substring(4, 7)}-${d.substring(7)}"
            else -> "+7 (${d.substring(1, 4)}) ${d.substring(4, 7)}-${d.substring(7, 9)}-${d.substring(9, 11)}"
        }
    }

    /** Маска от сервера или локальная: +7 ••• ••• 45 67. */
    fun masked(raw: String?): String {
        val d = normalize(raw)
        if (d.length != 11) return raw.orEmpty()
        return "+7 ••• ••• ${d.substring(7, 9)} ${d.substring(9, 11)}"
    }
}
