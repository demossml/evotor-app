package ru.sixthcup.evotor.data

import ru.sixthcup.evotor.net.CatalogApi

/**
 * Add-ons for the item sheet of the selected size.
 *
 * 1) toppings already on the size
 * 2) sibling of the same card (variantGroup / groupId)
 * 3) last catalog payload: groupId or loose name -> byGroupId
 *    (volume from local #group=, enrich missed uuid/name — still show milk/syrup)
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
        val gid = selected.groupId
            ?: variants.mapNotNull { it.groupId }.firstOrNull()
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

    private fun sameCard(a: CatalogProduct, b: CatalogProduct): Boolean {
        if (a.variantGroup != null && a.variantGroup == b.variantGroup) return true
        if (a.groupId != null && a.groupId == b.groupId) return true
        return false
    }
}
