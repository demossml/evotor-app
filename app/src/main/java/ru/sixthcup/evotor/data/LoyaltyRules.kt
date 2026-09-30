package ru.sixthcup.evotor.data

object LoyaltyRules {
    fun freeAvailable(paidTotal: Int, freeUsed: Int, n: Int = 5): Int =
        ((paidTotal / n) - freeUsed).coerceAtLeast(0)

    fun progress(paidTotal: Int, n: Int = 5): Int = paidTotal % n

    fun couponDiscountKopecks(lineSumKopecks: Int, percent: Int?, fixedRub: Int?): Int {
        val byPercent = if (percent != null) lineSumKopecks * percent / 100 else 0
        val byFixed = if (fixedRub != null) fixedRub * 100 else 0
        return maxOf(byPercent, byFixed).coerceAtMost(lineSumKopecks)
    }

    /**
     * TZ §8 calculation.
     * cashbackUseRub — only if barista confirmed.
     */
    fun totals(
        cart: Cart,
        card: ClientCard?,
        applyFree: Boolean,
        cashbackUseRub: Int
    ): CartTotals {
        val lines = cart.snapshot()
        // If free not applied, ensure no line marked free for sum
        val lineSum = lines.sumOf { line ->
            if (line.isFree && applyFree) {
                // one free unit: charge (qty-1)*price
                line.product.priceKopecks * (line.qty - 1).coerceAtLeast(0)
            } else {
                line.product.priceKopecks * line.qty
            }
        }
        val freeValue = if (applyFree) {
            lines.find { it.isFree }?.product?.priceKopecks ?: 0
        } else 0

        val discount = if (card != null) {
            couponDiscountKopecks(lineSum, card.couponPercent, card.couponFixedRub)
        } else 0

        val afterCoupon = (lineSum - discount).coerceAtLeast(0)
        val afterFree = afterCoupon // free already reflected in lineSum above
        val cbK = cashbackUseRub.coerceIn(0, (card?.cashbackRub ?: 0)).coerceAtMost(afterFree / 100) * 100
        val toPay = (afterFree - cbK).coerceAtLeast(0)

        return CartTotals(
            lineSumKopecks = lineSum + freeValue, // gross before free for display optional
            discountKopecks = discount,
            freeValueKopecks = freeValue,
            cashbackUseRub = cbK / 100,
            toPayKopecks = toPay
        )
    }

    /** Paid drinks count for loyalty (eligible, not the free unit). */
    fun paidDrinks(cart: Cart, applyFree: Boolean): Int {
        return cart.snapshot().sumOf { line ->
            if (!line.product.isFreeEligible) 0
            else if (line.isFree && applyFree) (line.qty - 1).coerceAtLeast(0)
            else line.qty
        }
    }
}
