package ru.sixthcup.evotor.data

data class Product(
    val id: String,
    val name: String,
    val priceKopecks: Int,      // store in kopecks to avoid float
    val category: Category,
    val isFreeEligible: Boolean, // can be the "6th cup"
    val imageUrl: String? = null
) {
    val priceRub: Int get() = priceKopecks / 100
}

enum class Category(val title: String) {
    DRINKS("Напитки"),
    FOOD("Еда"),
    OTHER("Другое")
}
