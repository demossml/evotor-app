package ru.sixthcup.evotor.data

import java.math.BigDecimal

/**
 * Одна опция в группе выбора (молоко / сироп / сахар / добавки).
 * Общая модель для опций из схемы backend и для товаров-опций `#opt=` из номенклатуры Эвотор.
 */
data class OptionRule(
    val key: String,
    val price: BigDecimal,
    val evotorUuid: String?,
    val required: Boolean = false,
    val isDefault: Boolean = false,
    val zeroOption: Boolean = false,
    val available: Boolean = true,
    val maxCount: Int = 1,
) {
    /** Платная опция: zero-option и нулевая цена в чек отдельной позицией не идут. */
    val paid: Boolean get() = !zeroOption && price.signum() > 0

    /** Платную опцию без товара Эвотор выбрать нельзя: она не выгружена в номенклатуру. */
    val sellable: Boolean get() = available && (!paid || !evotorUuid.isNullOrBlank())
}

/**
 * Правила добавок из контракта каталога:
 * 1) milk / syrup / sugar — одиночный выбор, extra — до groupLimit опций;
 * 2) isDefault — предвыбран, required — обязателен (zero-option закрывает требование);
 * 3) платная опция без evotorUuid недоступна, available=false недоступна;
 * 4) одну и ту же опцию нельзя добавить дважды.
 *
 * ТРАКТОВКА `maxCount` (осознанное решение, см. docs/CASHIER-APP.md):
 * в контракте `maxCount` — предел количества одной опции, здесь он читается как предел числа
 * РАЗНЫХ опций в группе (две порции одного сиропа в кофейне не продаются, а повторный тап
 * по той же опции запрещён). Поэтому функция называется [groupLimit], а не `limit`,
 * чтобы расхождение с формулировкой контракта не искали как баг. Если понадобится
 * количество у одной опции — это отдельная задача с изменением состава чека.
 */
object ToppingRules {

    fun ruleOf(t: Topping): OptionRule = OptionRule(
        key = "t:${t.id}",
        price = t.priceRub,
        evotorUuid = t.evotorUuid?.takeIf { it.isNotBlank() },
        required = t.required,
        isDefault = t.isDefault,
        zeroOption = t.zeroOption,
        available = t.available,
        maxCount = t.maxCount.coerceAtLeast(1),
    )

    /**
     * Предвыбор при открытии карточки: только isDefault или бесплатный вариант обязательной группы.
     * Платную обязательную опцию сами не отмечаем — иначе в чек попадёт сумма, которую гость не выбирал:
     * такую группу кассир обязан выбрать явно (иначе [blockers] не даст отправить позицию).
     */
    fun defaultOf(list: List<OptionRule>): OptionRule? =
        list.firstOrNull { it.isDefault && it.sellable }
            ?: list.firstOrNull { it.required && it.zeroOption && it.sellable }

    /**
     * Предел числа РАЗНЫХ опций в группе (0 — выбирать нечего).
     * Это трактовка контрактного `maxCount` для кассы: см. KDoc класса.
     */
    fun groupLimit(list: List<OptionRule>): Int =
        list.filter { it.sellable }.maxOfOrNull { it.maxCount } ?: 0

    /**
     * `required` — свойство ГРУППЫ: группа обязательна, если хотя бы одна её опция помечена required.
     * Группа закрыта, если выбрана ЛЮБАЯ доступная опция этой группы (включая zero-option):
     * у молока обязательной может быть «Обычное», а гость выбирает «Овсяное» — это валидный выбор.
     */
    fun requiredSatisfied(list: List<OptionRule>, selectedKeys: Set<String>): Boolean {
        if (list.none { it.required }) return true
        return list.any { it.sellable && it.key in selectedKeys }
    }

    /** Можно ли добавить опцию к текущему набору: доступна, не выбрана и лимит группы не исчерпан. */
    fun canSelect(list: List<OptionRule>, selectedKeys: Set<String>, key: String): Boolean {
        val rule = list.firstOrNull { it.key == key } ?: return false
        if (!rule.sellable || key in selectedKeys) return false
        val max = groupLimit(list)
        if (max <= 0) return false
        val used = selectedKeys.count { k -> list.any { it.key == k } }
        return used < max
    }

    /** Причины, по которым текущий набор нельзя отдать в чек. Пустой список — можно. */
    fun blockers(groups: List<Pair<String, List<OptionRule>>>, selectedKeys: Set<String>): List<String> {
        val out = ArrayList<String>(2)
        groups.forEach { (title, list) ->
            if (list.isEmpty()) return@forEach
            val required = list.any { it.required }
            if (required && !requiredSatisfied(list, selectedKeys)) {
                out.add(
                    if (list.none { it.sellable }) "Группа «$title»: нет доступных опций (нет товара Эвотор)"
                    else "Группа «$title»: выберите вариант",
                )
            }
            val max = groupLimit(list)
            if (max > 0 && selectedKeys.count { k -> list.any { it.key == k } } > max) {
                out.add("Группа «$title»: не больше $max")
            }
        }
        return out
    }
}
