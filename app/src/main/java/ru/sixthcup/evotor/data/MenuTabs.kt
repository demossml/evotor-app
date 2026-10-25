package ru.sixthcup.evotor.data

import ru.sixthcup.evotor.net.CatalogApi

/**
 * Вкладки (чипы) групп на главном экране кассы.
 *
 * Источник — блок `menu` каталога (админка → Меню → Группы): `order` задаёт порядок,
 * `showAllTab` — показывать ли чип «Всё». Без `menu` в ответе (старый backend или
 * старый кэш) поведение прежнее: чип «Всё» есть, группы по алфавиту.
 */
object MenuTabs {

    /** Чип «Всё»: по умолчанию включён — как было до появления настройки. */
    fun showAll(menu: CatalogApi.Menu?): Boolean = menu?.showAllTab ?: true

    /**
     * Порядок чипов: сначала группы из `menu.order` в заданном порядке,
     * затем остальные по алфавиту (правило контракта: «группы вне списка — в конце»).
     *
     * Группы из `menu.hidden` в результат не попадают вовсе — ни в порядок, ни в хвост:
     * их товары остаются в `products[]`, но чип на кассе не рисуется.
     *
     * Группа ищется в `menu.order` по своему названию; если папка Эвотора названа иначе,
     * чем категория в админке, — по категории backend у товаров этой группы.
     */
    fun order(
        groups: List<String>,
        products: List<CatalogProduct>,
        menu: CatalogApi.Menu?,
    ): List<String> {
        val hidden = menu?.hidden.orEmpty().map(::normalize).toSet()
        val visible = if (hidden.isEmpty()) groups else groups.filterNot { isHidden(it, products, hidden) }
        val order = menu?.order.orEmpty()
        if (order.isEmpty()) return visible.sorted()
        val rank = HashMap<String, Int>()
        order.forEachIndexed { i, name ->
            val key = normalize(name)
            // minSdk 23: putIfAbsent из Java 8 доступен только с API 24.
            if (!rank.containsKey(key)) rank[key] = i
        }

        val byGroupCategory = HashMap<String, Int>()
        for (p in products) {
            val g = p.groupName ?: continue
            val r = p.categoryName?.let { rank[normalize(it)] } ?: continue
            val prev = byGroupCategory[g]
            if (prev == null || r < prev) byGroupCategory[g] = r
        }
        return visible.sortedWith(
            compareBy({ rank[normalize(it)] ?: byGroupCategory[it] ?: Int.MAX_VALUE }, { it }),
        )
    }

    /**
     * Чип скрыт, если скрыто его название, либо все товары чипа пришли из одной скрытой
     * категории backend. Смешанную группу (папка Эвотора поверх нескольких категорий)
     * не скрываем: спрятать половину товаров касса не может.
     */
    private fun isHidden(group: String, products: List<CatalogProduct>, hidden: Set<String>): Boolean {
        if (normalize(group) in hidden) return true
        val cats = products.asSequence()
            .filter { it.groupName == group }
            .mapNotNull { it.categoryName }
            .map(::normalize)
            .distinct()
            .toList()
        return cats.size == 1 && cats.first() in hidden
    }

    /** Сравнение названий групп: регистр, «ё» и лишние пробелы не должны мешать. */
    internal fun normalize(s: String): String =
        s.trim().lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ")
}
