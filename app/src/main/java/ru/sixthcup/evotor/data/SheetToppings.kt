package ru.sixthcup.evotor.data

import ru.sixthcup.evotor.net.CatalogApi

/**
 * Схема добавок принадлежит карточке объёмов, а не отдельному uuid размера:
 * свои toppings → toppings соседа той же карточки → toppings группы backend (`groupId`).
 */
object SheetToppings {
    fun forSheet(
        selected: CatalogProduct,
        variants: List<CatalogProduct>,
        payload: CatalogApi.Payload? = null,
    ): List<Topping> {
        if (selected.toppings.isNotEmpty()) return selected.toppings
        val sibling = variants.firstOrNull {
            it.uuid != selected.uuid && it.toppings.isNotEmpty() && sameCard(it, selected)
        }?.toppings
        if (!sibling.isNullOrEmpty()) return sibling

        val pl = payload ?: return emptyList()
        val gid = cardGroupId(selected, pl)
            ?: variants.firstNotNullOfOrNull { cardGroupId(it, pl) }
            ?: Names.looseNameGroupId(Names.normalize(selected.name), pl.nameGroupId)
            ?: variants.firstNotNullOfOrNull {
                Names.looseNameGroupId(Names.normalize(it.name), pl.nameGroupId)
            }
        if (gid != null) {
            val fromGroup = pl.byGroupId[gid]
            if (!fromGroup.isNullOrEmpty()) return fromGroup
        }
        return emptyList()
    }

    fun effective(
        selected: CatalogProduct,
        variants: List<CatalogProduct>,
        payload: CatalogApi.Payload? = null,
    ): CatalogProduct {
        val tops = forSheet(selected, variants, payload)
        if (tops.isEmpty()) return selected
        if (tops === selected.toppings) return selected
        return selected.copy(
            toppings = tops,
            supportsMods = selected.supportsMods || tops.isNotEmpty(),
        )
    }

    /** Товар каталога 6.7 для размера: uuid точки → артикул `sc-<id>` → точное имя (запасной путь). */
    fun identity(p: CatalogProduct, payload: CatalogApi.Payload): CatalogApi.Enrich? {
        payload.byUuid[p.uuid]?.let { return it }
        val article = p.article?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        article?.let { payload.byArticle[it] }?.let { return it }
        return payload.byNameExact[Names.normalizeExact(p.name)]
    }

    /** groupId карточки: локальный → identity → `groups[]` (uuid/productId). */
    private fun cardGroupId(p: CatalogProduct, payload: CatalogApi.Payload): Int? {
        p.groupId?.let { return it }
        val e = identity(p, payload)
        return e?.groupId
            ?: payload.groupByUuid[p.uuid]
            ?: e?.productId?.let { payload.groupByProductId[it] }
    }

    private fun sameCard(a: CatalogProduct, b: CatalogProduct): Boolean {
        if (a.variantGroup != null && a.variantGroup == b.variantGroup) return true
        if (a.groupId != null && a.groupId == b.groupId) return true
        return false
    }
}
