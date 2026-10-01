package ru.sixthcup.evotor.data

/**
 * Add-on (syrup, topping, milk). Later loaded from backend per product/recipe.
 * Local seed until admin configures modifiers.
 */
data class Modifier(
    val id: String,
    val name: String,
    val priceKopecks: Int,
    val group: ModifierGroup
)

enum class ModifierGroup(val title: String) {
    SYRUP("Сиропы"),
    TOPPING("Топпинги"),
    MILK("Молоко / альтернатива"),
    OTHER("Дополнительно")
}

/**
 * Which modifiers to offer for a product.
 * Temporary local rules → replace with directory.product.modifierIds from API.
 */
object ModifierCatalog {
    val all: List<Modifier> = listOf(
        Modifier("syr_van", "Ваниль", 40_00, ModifierGroup.SYRUP),
        Modifier("syr_car", "Карамель", 40_00, ModifierGroup.SYRUP),
        Modifier("syr_haz", "Лесной орех", 40_00, ModifierGroup.SYRUP),
        Modifier("syr_coc", "Кокос", 40_00, ModifierGroup.SYRUP),
        Modifier("top_whip", "Взбитые сливки", 50_00, ModifierGroup.TOPPING),
        Modifier("top_choc", "Шоколадная крошка", 30_00, ModifierGroup.TOPPING),
        Modifier("milk_oat", "Овсяное молоко", 50_00, ModifierGroup.MILK),
        Modifier("milk_coc", "Кокосовое молоко", 50_00, ModifierGroup.MILK),
        Modifier("milk_soy", "Соевое молоко", 40_00, ModifierGroup.MILK)
    )

    fun forProduct(product: Product): List<Modifier> {
        // Until backend: all drink-eligible products get full modifier sheet
        if (!product.isFreeEligible && product.category != Category.DRINKS) return emptyList()
        return all
    }

    fun byIds(ids: Collection<String>): List<Modifier> =
        ids.mapNotNull { id -> all.find { it.id == id } }
}
