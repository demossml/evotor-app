package ru.sixthcup.evotor.data

import ru.sixthcup.evotor.net.OrderApi
import java.math.BigDecimal

/**
 * Перенос позиций предзаказа в текущую корзину — чистая логика, без Android.
 *
 * Правила:
 * 1. цена берётся из номенклатуры терминала ([CatalogProduct.priceRub]), а не из оценки заказа;
 * 2. позиция без `evotorProductUuid` в чек не попадает — возвращается в [Plan.missingUuid];
 * 3. товар, которого нет в номенклатуре точки, — [Plan.notFound];
 * 4. модификаторы не превращаются в фискальные позиции (у них нет uuid): их текст уходит
 *    в `recipe` строки, как `modsText()` при ручной конфигурации; `sendToReceipt=false` не печатаем.
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
    )

    data class Plan(
        val orderId: String,
        val lines: List<Line>,
        val missingUuid: List<String>,
        val notFound: List<String>,
    ) {
        val added: Int get() = lines.size
        val hasAnything: Boolean get() = lines.isNotEmpty()
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
            val key = "pre:$orderId:${item.lineId.ifBlank { index.toString() }}"
            if (key in alreadyInCart) return@forEachIndexed
            lines.add(
                Line(
                    productUuid = product.uuid,
                    name = product.name,
                    priceRub = product.priceRub,
                    quantity = BigDecimal(item.quantity.coerceAtLeast(1).toString()),
                    recipe = recipeOf(item),
                    cartKey = key,
                    freeEligible = product.freeEligible,
                ),
            )
        }
        return Plan(orderId, lines, missing, notFound)
    }

    /** Текст для строки чека: конфигурация заказа + печатаемые модификаторы. */
    fun recipeOf(item: OrderApi.Item): String? {
        val parts = ArrayList<String>(4)
        if (item.configText.isNotBlank()) parts.add(item.configText.trim())
        if (item.volumeLabel != null && item.configText.contains(item.volumeLabel, ignoreCase = true).not()) {
            parts.add(item.volumeLabel)
        }
        item.modifiers
            .filter { it.sendToReceipt && it.name.isNotBlank() }
            .forEach { m ->
                if (parts.none { it.contains(m.name, ignoreCase = true) }) parts.add(m.name)
            }
        return parts.joinToString(" · ").takeIf { it.isNotBlank() }
    }

    /** Сообщение кассиру по результату попытки. */
    fun notice(plan: Plan): String = when {
        plan.hasAnything && plan.missingUuid.isEmpty() && plan.notFound.isEmpty() ->
            "В чеке: ${plan.added} поз."
        plan.hasAnything -> buildString {
            append("В чеке: ${plan.added} поз.")
            if (plan.missingUuid.isNotEmpty()) append(". Без UUID Эвотора: ${plan.missingUuid.joinToString(", ")}")
            if (plan.notFound.isNotEmpty()) append(". Нет в кассе: ${plan.notFound.joinToString(", ")}")
        }
        plan.missingUuid.isNotEmpty() || plan.notFound.isNotEmpty() -> buildString {
            append("Не добавлено ни одной позиции")
            if (plan.missingUuid.isNotEmpty()) append(". Без UUID Эвотора: ${plan.missingUuid.joinToString(", ")}")
            if (plan.notFound.isNotEmpty()) append(". Нет в кассе: ${plan.notFound.joinToString(", ")}")
        }
        else -> "В предзаказе нет позиций"
    }
}
