package ru.sixthcup.evotor.data

import java.math.BigDecimal

data class CartLine(
    val productUuid: String,
    val name: String,
    val priceRub: BigDecimal,
    val quantity: BigDecimal = BigDecimal.ONE,
    val recipe: String? = null,
    val freeEligible: Boolean = false,
) {
    fun lineTotal(): BigDecimal = priceRub.multiply(quantity)
}

object Cart {
    private val lines = linkedMapOf<String, CartLine>()

    fun all(): List<CartLine> = lines.values.toList()
    fun clear() = lines.clear()
    fun isEmpty() = lines.isEmpty()

    fun add(line: CartLine) {
        val old = lines[line.productUuid]
        if (old == null) lines[line.productUuid] = line
        else lines[line.productUuid] = old.copy(quantity = old.quantity.add(line.quantity))
    }

    fun remove(productUuid: String) {
        lines.remove(productUuid)
    }

    fun setQty(productUuid: String, qty: BigDecimal) {
        val old = lines[productUuid] ?: return
        if (qty <= BigDecimal.ZERO) lines.remove(productUuid)
        else lines[productUuid] = old.copy(quantity = qty)
    }

    fun gross(): BigDecimal =
        lines.values.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.lineTotal()) }
}
