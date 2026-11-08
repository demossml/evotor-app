package ru.sixthcup.evotor.data

import ru.sixthcup.evotor.net.OrderApi
import java.math.BigDecimal

/**
 * Перенос позиций предзаказа в текущую корзину — чистая логика, без Android.
 *
 * Правила (промпт 2.4):
 * 1. цена берётся из номенклатуры терминала ([CatalogProduct.priceRub]), а не из оценки заказа;
 * 2. позиция без `evotorProductUuid` в чек не попадает — [Plan.missingUuid];
 * 3. товар, которого нет в номенклатуре точки, — [Plan.notFound];
 * 4. модификатор с `sendToReceipt=true` и `evotorUuid` добавляется **отдельной позицией** по своему uuid
 *    (цена — из номенклатуры); без uuid — только предупреждение, основная позиция разрешена;
 * 5. модификаторы с `sendToReceipt=false` (обычное молоко, бесплатные) в чек не идут вовсе;
 * 6. повторное нажатие «В чек» не дублирует строки (ключ `pre:<orderId>:<lineId>`).
 */
object PreorderPull {

    /** Опция-выбор без цены (корица): печатается отдельно и **никогда** не становится позицией чека. */
    const val GROUP_CHOICE = "choice"

    /** Позиция-опция, которая уйдёт отдельной строкой (для группировки в диалоге). */
    data class ReceiptModifier(val groupKey: String?, val name: String)

    fun isChoice(m: OrderApi.Modifier): Boolean =
        m.groupKey?.trim()?.equals(GROUP_CHOICE, ignoreCase = true) == true

    /**
     * Выбор без цены для печати: `title` — название схемы, `label` — выбранный вариант.
     * Если backend прислал в `group` сам ключ (`choice`), название схемы не выдумываем.
     */
    fun choiceOf(item: OrderApi.Item): Pair<String?, String?>? {
        val m = item.modifiers.firstOrNull { isChoice(it) } ?: return null
        val label = m.name.trim().ifBlank { return null }
        val rawTitle = m.group.trim()
        val title = rawTitle.takeIf {
            it.isNotEmpty() && !it.equals(GROUP_CHOICE, ignoreCase = true) &&
                !it.equals(m.groupKey.orEmpty(), ignoreCase = true)
        }
        return title to label
    }

    data class Line(
        val productUuid: String,
        val name: String,
        val priceRub: BigDecimal,
        val quantity: BigDecimal,
        val recipe: String?,
        /** Ключ строки: один предзаказ не должен дублироваться при повторном нажатии. */
        val cartKey: String,
        /** Как у ручного добавления: флаг «можно в подарок» берём у товара каталога. */
        val freeEligible: Boolean,
        /** Модификатор — отдельная позиция чека (не основная строка напитка). */
        val isModifier: Boolean = false,
        /** Выбор без цены (корица): печатается PrintExtra, в чек как позиция не идёт. */
        val choiceTitle: String? = null,
        val choiceLabel: String? = null,
    )

    data class Plan(
        val orderId: String,
        val lines: List<Line>,
        /** Позиции без UUID Эвотор: кассир добавляет вручную. */
        val missingUuid: List<String>,
        /** Позиции/опции, которых нет в номенклатуре точки. */
        val notFound: List<String>,
        /** Платные опции без UUID: предупреждение, но основная позиция разрешена. */
        val modifierNoUuid: List<String>,
        /** Опции, добавленные отдельными позициями: только для группировки в диалоге. */
        val receiptModifiers: List<ReceiptModifier> = emptyList(),
    ) {
        val added: Int get() = lines.size
        val itemLines: Int get() = lines.count { !it.isModifier }
        val hasAnything: Boolean get() = lines.isNotEmpty()
        val totalRub: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.priceRub.multiply(l.quantity)) }
    }

    fun plan(
        orderId: String,
        items: List<OrderApi.Item>,
        catalogByUuid: Map<String, CatalogProduct>,
        alreadyInCart: Set<String> = emptySet(),
    ): Plan {
        val lines = ArrayList<Line>(items.size)
        val missing = ArrayList<String>(0)
        val notFound = ArrayList<String>(0)
        val modifierNoUuid = ArrayList<String>(0)
        val receiptMods = ArrayList<ReceiptModifier>(0)

        items.forEachIndexed { index, item ->
            val uuid = item.evotorProductUuid?.trim().orEmpty()
            if (uuid.isEmpty()) {
                missing.add(item.name)
                return@forEachIndexed
            }
            val product = catalogByUuid[uuid]
            if (product == null) {
                notFound.add(item.name)
                return@forEachIndexed
            }
            val baseKey = "pre:$orderId:${item.lineId.ifBlank { index.toString() }}"
            if (baseKey in alreadyInCart) return@forEachIndexed
            val choice = choiceOf(item)
            lines.add(
                Line(
                    productUuid = product.uuid,
                    name = product.name,
                    priceRub = product.priceRub,
                    quantity = BigDecimal(item.quantity.coerceAtLeast(1).toString()),
                    recipe = recipeOf(item),
                    cartKey = baseKey,
                    freeEligible = product.freeEligible,
                    choiceTitle = choice?.first,
                    choiceLabel = choice?.second,
                ),
            )
            // Платные опции — отдельными позициями по своему uuid Эвотор.
            item.modifiers.forEachIndexed { mi, m ->
                if (!m.sendToReceipt) return@forEachIndexed
                if (isChoice(m)) return@forEachIndexed
                val mUuid = m.evotorUuid?.trim().orEmpty()
                if (mUuid.isEmpty()) {
                    modifierNoUuid.add("${item.name}: ${m.name}")
                    return@forEachIndexed
                }
                val mProduct = catalogByUuid[mUuid]
                if (mProduct == null) {
                    notFound.add(m.name)
                    return@forEachIndexed
                }
                val mKey = "$baseKey:m:${m.id.ifBlank { mi.toString() }}"
                if (mKey in alreadyInCart) return@forEachIndexed
                lines.add(
                    Line(
                        productUuid = mProduct.uuid,
                        name = mProduct.name,
                        priceRub = mProduct.priceRub,
                        quantity = BigDecimal(item.quantity.coerceAtLeast(1).toString()),
                        recipe = item.name,
                        cartKey = mKey,
                        freeEligible = false,
                        isModifier = true,
                    ),
                )
                receiptMods.add(ReceiptModifier(m.groupKey, m.name))
            }
        }
        return Plan(orderId, lines, missing, notFound, modifierNoUuid, receiptMods)
    }

    /** Текст для строки чека: конфигурация заказа + печатаемые модификаторы без uuid. */
    fun recipeOf(item: OrderApi.Item): String? {
        val parts = ArrayList<String>(4)
        if (item.configText.isNotBlank()) parts.add(item.configText.trim())
        if (item.volumeLabel != null && item.configText.contains(item.volumeLabel, ignoreCase = true).not()) {
            parts.add(item.volumeLabel)
        }
        // В рецепт попадают только опции, которые не ушли отдельной позицией.
        item.modifiers
            .filter { it.sendToReceipt && !isChoice(it) && it.name.isNotBlank() && it.evotorUuid.isNullOrBlank() }
            .forEach { m -> if (parts.none { it.contains(m.name, ignoreCase = true) }) parts.add(m.name) }
        return parts.joinToString(" · ").takeIf { it.isNotBlank() }
    }

    /**
     * Группировка опций для диалога «В чек»: `groupKey` — только оформление, в чек не уходит.
     * `choice` здесь не встречается: такие опции в чек не добавляются никогда.
     */
    fun groupedModifiers(list: List<ReceiptModifier>): String {
        if (list.isEmpty()) return ""
        fun label(key: String?) = when (key?.trim()?.lowercase()) {
            "milk" -> "молоко"
            "addon", "extra" -> "добавки"
            "syrup" -> "сироп"
            else -> "опции"
        }
        val order = listOf("молоко", "сироп", "добавки", "опции")
        return list.groupBy { label(it.groupKey) }
            .toList()
            .sortedBy { (group, _) -> order.indexOf(group).let { if (it < 0) order.size else it } }
            .joinToString("; ") { (group, items) -> "$group: ${items.joinToString(", ") { it.name }}" }
    }

    /** Сообщение кассиру по результату попытки (диалог подтверждения). */
    fun notice(plan: Plan): String = buildString {
        if (plan.hasAnything) {
            append("Добавлено ${plan.added} поз. Сумма по кассе: ${rub(plan.totalRub)} ₽")
        } else {
            append("Ни одна позиция не добавлена")
        }
        if (plan.receiptModifiers.isNotEmpty()) {
            append(". Опции: ${groupedModifiers(plan.receiptModifiers)}")
        }
        if (plan.missingUuid.isNotEmpty()) {
            append(". Нет UUID на точке: ${plan.missingUuid.joinToString(", ")}")
        }
        if (plan.notFound.isNotEmpty()) {
            append(". Нет в кассе: ${plan.notFound.joinToString(", ")}")
        }
        if (plan.modifierNoUuid.isNotEmpty()) {
            append(". Опции без UUID добавьте вручную: ${plan.modifierNoUuid.joinToString(", ")}")
        }
    }

    private fun rub(v: BigDecimal): String = v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
}
