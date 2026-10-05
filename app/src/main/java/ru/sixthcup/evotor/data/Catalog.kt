package ru.sixthcup.evotor.data

/**
 * Seed menu for MVP. Later replaced by backend /api/cashier/menu.
 * imageUrl left null — UI shows color placeholder (TZ: photos later).
 */
object Catalog {
    val products: List<Product> = listOf(
        Product("cap", "Капучино", 180_00, Category.DRINKS, isFreeEligible = true),
        Product("lat", "Латте", 190_00, Category.DRINKS, isFreeEligible = true),
        Product("ame", "Американо", 150_00, Category.DRINKS, isFreeEligible = true),
        Product("esp", "Эспрессо", 120_00, Category.DRINKS, isFreeEligible = true),
        Product("flt", "Флэт уайт", 200_00, Category.DRINKS, isFreeEligible = true),
        Product("cocoa", "Какао", 170_00, Category.DRINKS, isFreeEligible = true),
        Product("tea", "Чай", 140_00, Category.DRINKS, isFreeEligible = true),
        Product("croissant", "Круассан", 120_00, Category.FOOD, isFreeEligible = false),
        Product("cookie", "Печенье", 80_00, Category.FOOD, isFreeEligible = false),
        Product("sandwich", "Сэндвич", 220_00, Category.FOOD, isFreeEligible = false),
        Product("water", "Вода 0.5", 70_00, Category.OTHER, isFreeEligible = false),
        Product("syrup", "Сироп доп.", 40_00, Category.OTHER, isFreeEligible = false)
    )

    fun byId(id: String) = products.find { it.id == id }
}
