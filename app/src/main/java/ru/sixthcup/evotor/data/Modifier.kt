package ru.sixthcup.evotor.data

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
    OTHER("Дополнительно");

    companion object {
        fun fromKey(key: String): ModifierGroup = when (key.lowercase()) {
            "syrup" -> SYRUP
            "topping" -> TOPPING
            "milk" -> MILK
            else -> OTHER
        }
    }
}

data class ModifierScheme(
    val id: Int,
    val name: String,
    val modifierIds: List<String>
)

/** Runtime catalogs from directory (not hardcoded seed). */
object ModifierCatalog {
    @Volatile var all: List<Modifier> = emptyList()
    @Volatile var schemes: Map<Int, ModifierScheme> = emptyMap()

    fun forProduct(product: Product): List<Modifier> {
        val sid = product.modifierSchemeId ?: return emptyList()
        val scheme = schemes[sid] ?: return emptyList()
        return scheme.modifierIds.mapNotNull { id -> all.find { it.id == id } }
    }

    fun replaceFromDirectory(mods: List<Modifier>, sch: List<ModifierScheme>) {
        all = mods
        schemes = sch.associateBy { it.id }
    }
}
