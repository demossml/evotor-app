package ru.sixthcup.evotor.data

data class CartLine(
    val product: Product,
    var qty: Int,
    /** this line is the free 6th cup (price 0 in fiscal) */
    var isFree: Boolean = false
) {
    fun lineSumKopecks(): Int =
        if (isFree) 0 else product.priceKopecks * qty
}

data class CartTotals(
    val lineSumKopecks: Int,
    val discountKopecks: Int,
    val freeValueKopecks: Int,
    val cashbackUseRub: Int,
    val toPayKopecks: Int
)

class Cart {
    private val lines = linkedMapOf<String, CartLine>()

    fun snapshot(): List<CartLine> = lines.values.map { it.copy() }

    fun add(product: Product) {
        val existing = lines[product.id]
        if (existing != null) existing.qty += 1
        else lines[product.id] = CartLine(product, 1)
    }

    fun setQty(productId: String, qty: Int) {
        val line = lines[productId] ?: return
        if (qty <= 0) lines.remove(productId)
        else line.qty = qty
    }

    fun clear() {
        lines.clear()
    }

    fun isEmpty() = lines.isEmpty()

    fun markFree(productId: String?) {
        lines.values.forEach { it.isFree = false }
        if (productId != null) {
            lines[productId]?.let {
                if (it.product.isFreeEligible) {
                    it.isFree = true
                    // free applies to one unit conceptually
                    if (it.qty < 1) it.qty = 1
                }
            }
        }
    }

    fun freeLine(): CartLine? = lines.values.find { it.isFree }
}
