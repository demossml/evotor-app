package ru.sixthcup.evotor.data

import ru.sixthcup.evotor.net.OrderApi

/**
 * Единый путь «предзаказ → корзина»: панель заказа и авто-подтягивание по QR используют
 * один и тот же расчёт, чтобы поведение не разошлось.
 *
 * Чистая сборка плана ([prepare]) тестируется без Android; запись в [Cart] — [commit],
 * вызывать на UI-потоке.
 */
object PreorderPuller {

    data class Prepared(
        val orderId: String,
        val shortCode: String,
        val plan: PreorderPull.Plan,
        val benefit: OrderBenefit?,
        /** Позиции заказа с UUID — для сессии, когда строки уже лежат в корзине. */
        val orderItems: List<PreorderPullSession.Item>,
    ) {
        /** Есть проблемы, о которых кассир обязан узнать (нет UUID / нет в кассе). */
        val hasIssues: Boolean
            get() = plan.missingUuid.isNotEmpty() || plan.notFound.isNotEmpty()

        /** Можно молча наполнить корзину: ничего не теряется и есть что класть. */
        val canSilentPull: Boolean
            get() = !hasIssues && (plan.hasAnything || plan.alreadyPresent.isNotEmpty())
    }

    fun prepare(
        order: OrderApi.Order,
        catalog: List<CatalogProduct>,
        alreadyInCart: Set<String> = emptySet(),
    ): Prepared = Prepared(
        orderId = order.id,
        shortCode = order.shortCode,
        plan = PreorderPull.plan(order.id, order.items, catalog.associateBy { it.uuid }, alreadyInCart),
        benefit = OrderBenefit.from(order),
        orderItems = order.items.mapNotNull { i ->
            i.evotorProductUuid?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { PreorderPullSession.Item(it, i.name, i.quantity.coerceAtLeast(1)) }
        },
    )

    /** Записать подготовленный план в корзину и сессию (один раз, на UI-потоке). */
    fun commit(p: Prepared) {
        p.plan.lines.forEach { l ->
            Cart.add(
                CartLine(
                    productUuid = l.productUuid,
                    name = l.name,
                    priceRub = l.priceRub,
                    quantity = l.quantity,
                    recipe = l.recipe,
                    freeEligible = l.freeEligible,
                    cartKey = l.cartKey,
                    choiceTitle = l.choiceTitle,
                    choiceLabel = l.choiceLabel,
                ),
            )
        }
        val items = p.plan.lines
            .map { PreorderPullSession.Item(it.productUuid, it.name, it.quantity.toInt()) }
            .ifEmpty { p.orderItems }
        PreorderPullSession.set(p.orderId, p.shortCode, items, p.benefit)
    }
}

/**
 * Правило, когда скан QR можно сразу превращать в корзину, а когда нужна панель заказа.
 *
 * Только `ready` и только пустая корзина: иначе касса смешает два заказа, а у `draft`/`accepted`/
 * `cooking` ещё есть статусные действия, которые делает бариста в панели.
 */
object PreorderAutoPull {
    fun shouldPull(status: String?, cartEmpty: Boolean): Boolean =
        cartEmpty && status?.trim()?.equals(PreorderQueue.READY, ignoreCase = true) == true

    fun allowsSilentPull(prepared: PreorderPuller.Prepared): Boolean = prepared.canSilentPull
}
