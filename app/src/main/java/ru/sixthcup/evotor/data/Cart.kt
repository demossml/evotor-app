package ru.sixthcup.evotor.data

data class CartLine(
    val product: Product,
    var qty: Int,
    var isFree: Boolean = false,
    /** selected modifier ids (for display / future fiscal) */
    val modifierIds: List<String> = emptyList()
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

    fun add(product: Product, modifiers: List<Modifier> = emptyList()) {
        val modIds = modifiers.map { it.id }.sorted()
        val key = if (modIds.isEmpty()) product.id
        else product.id + "|" + modIds.joinToString(",")
        val extra = modifiers.sumOf { it.priceKopecks }
        val displayName = if (modifiers.isEmpty()) product.name
        else product.name + " · " + modifiers.joinToString(", ") { it.name }
        val lineProduct = product.copy(
            id = key,
            name = displayName,
            priceKopecks = product.priceKopecks + extra
        )
        val existing = lines[key]
        if (existing != null) existing.qty += 1
        else lines[key] = CartLine(lineProduct, 1, modifierIds = modIds)
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
                    if (it.qty < 1) it.qty = 1
                }
            }
        }
    }

    fun freeLine(): CartLine? = lines.values.find { it.isFree }
}
