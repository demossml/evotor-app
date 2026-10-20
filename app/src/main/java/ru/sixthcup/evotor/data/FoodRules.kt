package ru.sixthcup.evotor.data

/**
 * «Это еда» — для решения «показывать ли рекомендации (upsell) перед оплатой».
 * Раньше признак выводился эвристикой `!supportsMods && !freeEligible` — под него попадали и опции, и мерч.
 *
 * Приоритет (сверху вниз):
 *  1. `#nofood` в описании → нет; `#food` (без `=uuid`, это состав комбо) → да;
 *  2. флаг backend `isFood` (если пришёл);
 *  3. группа Эвотор / категория 6.7: «Еда», «Выпечка», «Десерты», «Снэки», «Завтраки», «Food», «Bakery»;
 *  4. слова еды в названии (круассан, сэндвич, чизкейк …).
 * Иначе — не еда. Чистый Kotlin, покрыт тестами.
 */
object FoodRules {
    private val NO_FOOD = Regex("#nofood(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    // `#food=<uuid>` — ссылка на еду в комбо, а не флаг.
    private val FOOD_FLAG = Regex("#food(?![\\p{L}\\p{N}_=:])", RegexOption.IGNORE_CASE)

    private val GROUP = listOf("еда", "еды", "выпечк", "десерт", "снэк", "снек", "сладк", "завтрак", "сэндвич", "сендвич", "food", "bakery", "dessert", "snack")
    private val NAME = listOf(
        "круассан", "торт", "пирог", "пирожн", "пирожок", "чизкейк", "сэндвич", "сендвич", "бутерброд", "булочк", "булка",
        "кекс", "маффин", "печень", "эклер", "сырник", "багет", "салат", "суп", "пончик", "донат", "вафл",
        "блин", "брауни", "шарлотк", "тост", "ролл", "киш", "слойк", "эчпочмак", "чебурек", "пицц",
    )

    private fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    private fun hit(ws: List<String>, keys: List<String>) = ws.any { w -> keys.any { w.startsWith(it) } }

    fun isFood(name: String, groupName: String?, categoryName: String?, description: String?, serverFlag: Boolean? = null): Boolean {
        val d = description.orEmpty()
        if (NO_FOOD.containsMatchIn(d)) return false
        if (FOOD_FLAG.containsMatchIn(d)) return true
        if (serverFlag != null) return serverFlag
        if (hit(words(groupName.orEmpty()), GROUP) || hit(words(categoryName.orEmpty()), GROUP)) return true
        return hit(words(name), NAME)
    }

    /** В корзине есть еда: позиция из [foodUuids] либо любая комбо-позиция (комбо = напиток + еда). */
    fun cartHasFood(lines: List<CartLine>, foodUuids: Set<String>): Boolean =
        lines.any { it.comboGroupId != null || it.productUuid in foodUuids }
}
