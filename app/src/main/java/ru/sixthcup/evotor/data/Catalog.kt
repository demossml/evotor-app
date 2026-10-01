package ru.sixthcup.evotor.data

/** Runtime catalog from backend GET /api/directory (cached). No hardcoded menu. */
object Catalog {
    @Volatile private var items: List<Product> = emptyList()
    val products: List<Product> get() = items
    fun replaceAll(list: List<Product>) { items = list.toList() }
    fun byId(id: String) = items.find { it.id == id }
    fun isEmpty() = items.isEmpty()
}
