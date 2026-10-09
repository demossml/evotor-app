package ru.sixthcup.evotor.data

import android.content.Context
import android.database.Cursor
import ru.evotor.framework.inventory.InventoryApi
import ru.evotor.framework.inventory.ProductItem
import ru.evotor.framework.inventory.ProductTable
import java.math.BigDecimal

data class CatalogProduct(
    val uuid: String,
    val name: String,
    val priceRub: BigDecimal,
    val allowToSell: Boolean,
    val recipe: String?,
    val description: String?,
    val article: String?,
    val freeEligible: Boolean = false,
)

/** Docs: InventoryApi / commodity DB. Never call from Activity.onCreate. */
object InventoryRepository {
    fun load(context: Context): List<CatalogProduct> {
        val out = mutableListOf<CatalogProduct>()
        context.contentResolver.query(ProductTable.URI, null, null, null, null)?.use { c ->
            val uuidCol = col(c, "UUID", "uuid")
            val nameCol = col(c, "NAME", "name")
            val priceCol = col(c, "PRICE_OUT", "priceOut", "PRICE", "price")
            val typeCol = col(c, "TYPE", "type")
            val allowCol = col(c, "ALLOW_TO_SELL", "allowToSell")
            while (c.moveToNext()) {
                val uuid = uuidCol?.let { c.getString(it) }?.trim().orEmpty()
                val name = nameCol?.let { c.getString(it) }?.trim().orEmpty()
                if (uuid.isEmpty() || name.isEmpty()) continue
                val type = typeCol?.let { c.getString(it) }.orEmpty()
                if (type.contains("GROUP", ignoreCase = true)) continue
                val price = priceCol?.let { idx ->
                    try { BigDecimal.valueOf(c.getDouble(idx)) } catch (_: Throwable) { BigDecimal.ZERO }
                } ?: BigDecimal.ZERO
                val allow = allowCol?.let { c.getInt(it) != 0 } ?: true
                if (!allow) continue
                var recipe: String? = null
                try {
                    val item = InventoryApi.getProductByUuid(context, uuid) as? ProductItem.Product
                    recipe = item?.description?.takeIf { it.isNotBlank() }
                } catch (_: Throwable) {
                }
                out.add(CatalogProduct(uuid, name, price, allow, recipe, recipe, null, freeEligible = true))
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun col(c: Cursor, vararg names: String): Int? {
        for (n in names) {
            val i = c.getColumnIndex(n)
            if (i >= 0) return i
        }
        return null
    }
}
