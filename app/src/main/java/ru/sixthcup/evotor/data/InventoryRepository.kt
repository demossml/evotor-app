package ru.sixthcup.evotor.data

import android.content.Context
import android.database.Cursor
import org.json.JSONObject
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
)

/**
 * Products from the terminal (synced from Evotor Cloud after admin push).
 * No hardcoded menu.
 */
object InventoryRepository {
    fun load(context: Context): List<CatalogProduct> {
        val out = mutableListOf<CatalogProduct>()
        context.contentResolver.query(ProductTable.URI, null, null, null, null)?.use { c ->
            val uuidCol = col(c, ProductTable.ROW_UUID, "UUID", "uuid")
            val nameCol = col(c, ProductTable.ROW_NAME, "NAME", "name")
            val priceCol = col(c, ProductTable.ROW_PRICE_OUT, "PRICE_OUT", "priceOut", "PRICE")
            val typeCol = col(c, ProductTable.ROW_TYPE, "TYPE", "type")
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
                val extra = readSixthCupExtra(context, uuid)
                out.add(
                    CatalogProduct(
                        uuid = uuid,
                        name = name,
                        priceRub = price,
                        allowToSell = allow,
                        recipe = extra?.optString("recipe")?.ifBlank { null },
                        description = extra?.optString("description")?.ifBlank { null },
                        article = extra?.optString("article")?.ifBlank { null },
                    ),
                )
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun get(context: Context, uuid: String): CatalogProduct? =
        load(context).find { it.uuid == uuid }

    private fun readSixthCupExtra(context: Context, productUuid: String): JSONObject? {
        return try {
            val product = InventoryApi.getProductByUuid(context, productUuid) as? ProductItem.Product ?: return null
            // Product extras vary by SDK; try reflection-safe name field if present
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun col(c: Cursor, vararg names: String): Int? {
        for (n in names) {
            val i = c.getColumnIndex(n)
            if (i >= 0) return i
        }
        return null
    }
}
