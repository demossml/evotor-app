package ru.sixthcup.evotor.data

import java.math.BigDecimal

/**
 * Сборка позиций чека из выбора в карточке товара.
 *
 * Чистая функция без Android: её проверяют unit-тесты, а `showItemSheet` только вызывает её
 * и складывает результат в [Cart]. Один и тот же код отвечает на вопросы
 * «что попадёт в чек» и «сколько это стоит».
 *
 * Правила (ТЗ «динамическая конфигурация напитков», §8):
 * 1. Объём — это **сам товар Эвотор** (`variant.uuid`), отдельная строка чека. Базовый товар
 *    карточки отдельно не пробивается: двойного списания нет.
 * 2. Опция идёт отдельной позицией только если она: доступна, не `zeroOption`, разрешена
 *    сервером (`sendToReceipt`), имеет uuid и не совпадает с самим объёмом.
 * 3. «Обычное молоко» — это `zeroOption` (явный флаг сервера, не догадка по названию или цене):
 *    в окне показывается, в чек не идёт, цену не добавляет.
 * 4. Цена берётся из номенклатуры/каталога, ничего не придумывается.
 */
object DrinkConfig {

    enum class Role { VOLUME, MILK, ADDON }

    data class Line(
        val uuid: String,
        val name: String,
        val priceRub: BigDecimal,
        /** `Topping.id`, если позиция пришла из схемы каталога. */
        val optionId: Int?,
        val role: Role,
    ) {
        val goesToReceipt: Boolean get() = uuid.isNotBlank()
    }

    private val MILK_KEYS = setOf("milk", "молоко", "milks")

    /** Пойдёт ли опция отдельной позицией в чек. */
    fun goesToReceipt(option: Topping, variantUuid: String): Boolean {
        if (!option.available || option.zeroOption || !option.sendToReceipt) return false
        val uuid = option.evotorUuid?.trim().orEmpty()
        return uuid.isNotEmpty() && uuid != variantUuid
    }

    fun isMilk(option: Topping): Boolean = option.groupKey.trim().lowercase() in MILK_KEYS

    /**
     * Строки для одной порции: объём + платное молоко + платные добавки.
     * Обычное молоко (zeroOption) сюда не попадает — оно остаётся только выбором рецепта.
     */
    fun receiptLines(variant: CatalogProduct, chosen: List<Topping>): List<Line> {
        val out = ArrayList<Line>(chosen.size + 1)
        out.add(Line(variant.uuid, variant.name, variant.priceRub, optionId = null, role = Role.VOLUME))
        val seen = HashSet<String>()
        seen.add(variant.uuid)
        for (t in chosen) {
            if (!goesToReceipt(t, variant.uuid)) continue
            val uuid = t.evotorUuid!!.trim()
            // Одна и та же позиция не должна попасть в чек дважды из одного добавления.
            if (!seen.add(uuid)) continue
            out.add(
                Line(
                    uuid = uuid,
                    name = "${t.name} (${variant.name})",
                    priceRub = t.priceRub,
                    optionId = t.id,
                    role = if (isMilk(t)) Role.MILK else Role.ADDON,
                ),
            )
        }
        return out
    }

    /**
     * Вариант с раздельными блоками конфигурации (молоко отдельно от добавок).
     * Правила те же: обычное молоко (`isFree`) и `sendToReceipt=false` в чек не идут.
     */
    fun receiptLines(
        variant: CatalogProduct,
        milk: MilkOption?,
        additions: List<AdditionOption>,
    ): List<Line> {
        val chosen = ArrayList<Topping>(additions.size + 1)
        milk?.let {
            chosen.add(
                Topping(
                    id = it.id,
                    name = it.label,
                    // Бесплатное молоко не имеет цены в конфигурации: цену даёт касса по uuid.
                    priceRub = BigDecimal.ZERO,
                    groupKey = "milk",
                    evotorUuid = it.evotorUuid,
                    isDefault = it.isDefault,
                    zeroOption = it.isFree,
                    available = it.available,
                    sendToReceipt = it.sendToReceipt,
                ),
            )
        }
        additions.forEach { a ->
            chosen.add(
                Topping(
                    id = a.id,
                    name = a.name,
                    priceRub = a.priceRub,
                    groupKey = a.groupKey,
                    maxCount = a.maxCount,
                    evotorUuid = a.evotorUuid,
                    required = a.required,
                    isDefault = a.isDefault,
                    zeroOption = a.zeroOption,
                    available = a.available,
                    sendToReceipt = a.sendToReceipt,
                ),
            )
        }
        return receiptLines(variant, chosen)
    }

    /** Итог по строкам — справочно для UI; деньги принимает Эвотор по фактическим ценам каталога. */
    fun total(lines: List<Line>): BigDecimal =
        lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.priceRub) }
}
