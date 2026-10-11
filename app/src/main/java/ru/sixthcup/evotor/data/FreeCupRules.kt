package ru.sixthcup.evotor.data

/**
 * Какие позиции могут быть «6-м бесплатным стаканом». Терминал не знает флаг `counts_as_cup` из админки,
 * поэтому правило строится по данным Inventory (по убыванию приоритета):
 *  1. Явная метка в описании товара: `#cup` → да, `#nocup` → нет.
 *  2. Еда / ретейл по словам в названии (круассан, торт, зерно, чайник …) → нет.
 *  3. Напиток по словам в названии (кофе, американо, капучино, латте, раф, флэт, чай …) → да.
 *  4. Название группы Эвотор: «Кофе/Чай/Напитки» → да; «Выпечка/Десерты/Еда» → нет.
 *  5. Иначе → нет (подарок не предлагается; backend всё равно перепроверяет).
 * Слова сравниваются по началу слова, регистр не важен.
 */
object FreeCupRules {
    private val FOOD = listOf(
        "круассан", "торт", "пирог", "пирожн", "пирожок", "чизкейк", "сэндвич", "сендвич", "бутерброд", "булочк", "булка",
        "кекс", "маффин", "печень", "десерт", "эклер", "сырник", "багет", "салат", "суп", "пончик", "донат", "вафл",
        "блин", "брауни", "шарлотк", "хлеб", "снэк", "батончик", "конфет", "зерн", "молот", "капсул", "мерч",
        "термос", "кружк", "пачк", "чайник", "кофеварк", "кофемолк", "фильтр",
    )
    private val DRINK = listOf(
        "кофе", "эспрессо", "американо", "капучино", "латте", "раф", "флэт", "флет", "макиато", "макиатто", "мокко",
        "кортадо", "ристретто", "лунго", "допио", "чай", "какао", "матча", "лимонад", "морс", "фреш", "смузи",
        "коктейл", "бамбл", "глинтвейн", "айс", "пуровер", "воронк",
    )
    private val GROUP_DRINK = listOf("кофе", "чай", "напит", "холодн", "горяч", "лимонад", "авторск")
    private val GROUP_FOOD = listOf("выпечк", "десерт", "еда", "снэк", "сладк", "ретейл", "розниц", "мерч")

    private fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    private fun hit(ws: List<String>, keys: List<String>) = ws.any { w -> keys.any { w.startsWith(it) } }

    fun isEligible(name: String, groupName: String?, description: String?): Boolean {
        val d = description.orEmpty().lowercase()
        if (d.contains("#nocup")) return false
        if (d.contains("#cup")) return true
        val nw = words(name)
        val lowered = name.lowercase()
        if (hit(nw, FOOD)) return false
        if (hit(nw, DRINK) || lowered.contains("горячий шоколад")) return true
        val gw = words(groupName.orEmpty())
        if (hit(gw, GROUP_FOOD)) return false
        if (hit(gw, GROUP_DRINK)) return true
        return false
    }
}
