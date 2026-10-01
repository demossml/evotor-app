package ru.sixthcup.evotor.data

data class Product(
    val id: String,
    val name: String,
    val priceKopecks: Int,
    val category: Category,
    val isFreeEligible: Boolean,
    val imageUrl: String? = null,
    val modifierSchemeId: Int? = null,
    val recipeText: String? = null,
    val recipeCostRub: Int? = null,
    val recipeSeconds: Int? = null
) {
    val priceRub: Int get() = priceKopecks / 100
}

enum class Category(val title: String) {
    DRINKS("Напитки"),
    FOOD("Еда"),
    OTHER("Другое")
}
