package ru.sixthcup.evotor.data

import ru.sixthcup.evotor.net.CatalogApi

/**
 * Контракт item-sheet: блок 1 — объём, блок 2 — молоко, блок 3 — сироп/схема.
 *
 * Карточка объёмов — одна сущность: схема добавок вешается на карточку целиком
 * ([cardVariants]), а не зависит от того, склеился ли каждый uuid размера.
 * Пустой блок допустим только когда схема пуста в данных backend; если каталог
 * успешно получен, а сопоставить карточку не удалось — это [Scheme.MAPPING_ERROR].
 */
object SheetContract {

    data class Groups(
        val milk: List<Topping> = emptyList(),
        val syrup: List<Topping> = emptyList(),
        val sugar: List<Topping> = emptyList(),
        val extra: List<Topping> = emptyList(),
    )

    enum class Scheme { OK, EMPTY_IN_DATA, MAPPING_ERROR }

    private val MILK = listOf("milk", "молоко")
    private val SYRUP = listOf("syrup", "сироп")
    private val SUGAR = listOf("sugar", "сахар")
    private val EXTRA = listOf("topping", "extra", "other", "добавки", "добавка")
    private val KNOWN = (MILK + SYRUP + SUGAR + EXTRA).toSet()

    /** `groupKey` схемы → секция sheet; неизвестные ключи уходят в «Добавки». */
    fun group(toppings: List<Topping>): Groups {
        val byKey = toppings.groupBy { it.groupKey.trim().lowercase() }
        fun take(keys: List<String>) = keys.flatMap { byKey[it].orEmpty() }
        val rest = byKey.filterKeys { it !in KNOWN }.values.flatten()
        return Groups(
            milk = take(MILK),
            syrup = take(SYRUP),
            sugar = take(SUGAR),
            extra = take(EXTRA) + rest,
        )
    }

    /** Все размеры карточки получают схему карточки (свою, соседа или группы backend). */
    fun cardVariants(variants: List<CatalogProduct>, payload: CatalogApi.Payload?): List<CatalogProduct> =
        variants.map { SheetToppings.effective(it, variants, payload) }

    fun topsPerVariant(variants: List<CatalogProduct>): List<Int> = variants.map { it.toppings.size }

    /**
     * Почему у карточки нет добавок.
     * @param catalogOk каталог в этой сессии получен по HTTP успешно (не кэш/офлайн).
     */
    fun scheme(
        cardVariants: List<CatalogProduct>,
        payload: CatalogApi.Payload?,
        catalogOk: Boolean,
    ): Scheme {
        if (cardVariants.any { it.toppings.isNotEmpty() }) return Scheme.OK
        if (!catalogOk || payload == null || cardVariants.size <= 1) return Scheme.EMPTY_IN_DATA
        // Пусто «по данным» — только если backend однозначно опознал размеры и у них схемы нет.
        val confirmedEmpty = cardVariants.all { v ->
            val e = SheetToppings.identity(v, payload) ?: return@all false
            val gid = e.groupId ?: v.groupId
            e.toppings.isEmpty() && (gid == null || payload.byGroupId[gid].isNullOrEmpty())
        }
        return if (confirmedEmpty) Scheme.EMPTY_IN_DATA else Scheme.MAPPING_ERROR
    }
}
