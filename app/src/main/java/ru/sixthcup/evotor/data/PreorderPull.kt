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
            lines.add(
                Line(
                    productUuid = product.uuid,
                    name = product.name,
                    priceRub = product.priceRub,
                    quantity = BigDecimal(item.quantity.coerceAtLeast(1).toString()),
                    recipe = recipeOf(item),
                    cartKey = baseKey,
                    freeEligible = product.freeEligible,
                ),
            )
            // Платные опции — отдельными позициями по своему uuid Эвотор.
            item.modifiers.forEachIndexed { mi, m ->
                if (!m.sendToReceipt) return@forEachIndexed
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
            }
        }
        return Plan(orderId, lines, missing, notFound, modifierNoUuid)
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
            .filter { it.sendToReceipt && it.name.isNotBlank() && it.evotorUuid.isNullOrBlank() }
            .forEach { m -> if (parts.none { it.contains(m.name, ignoreCase = true) }) parts.add(m.name) }
        return parts.joinToString(" · ").takeIf { it.isNotBlank() }
    }

    /** Сообщение кассиру по результату попытки (диалог подтверждения). */
    fun notice(plan: Plan): String = buildString {
        if (plan.hasAnything) {
            append("Добавлено ${plan.added} поз. Сумма по кассе: ${rub(plan.totalRub)} ₽")
        } else {
            append("Ни одна позиция не добавлена")
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
