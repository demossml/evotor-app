package ru.sixthcup.evotor.ui

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import ru.evotor.framework.inventory.InventoryApi
import ru.evotor.framework.inventory.ProductItem
import ru.evotor.framework.inventory.ProductTable
import ru.evotor.framework.receipt.Measure
import ru.evotor.framework.receipt.Position
import ru.evotor.framework.receipt.position.SettlementMethod
import java.math.BigDecimal
import java.util.UUID

/**
 * 6.7 catalog layer invoked by Evotor POS from its product chooser.
 * Products are read from the terminal inventory; no local hardcoded catalog exists.
 * ProductExtra with name "sixthcup" carries recipe/toppings/business metadata.
 */
class EvotorCatalogActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_IN_OPERATION_TYPE = "inOperationType"
        private const val EXTRA_POSITION = "EXTRA_POSITION"
        private const val EXTRA_OPERATION_TYPE = "EXTRA_OPERATION_TYPE"
    }

    private val operationTypeOrdinal: Int
        get() = intent.getIntExtra(EXTRA_IN_OPERATION_TYPE, 6)

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        setContentView(root)
        renderCatalog()
    }

    private fun renderCatalog() {
        root.removeAllViews()
        addTitle("6.7 Coffee — меню")
        addHint("Товары загружаются из номенклатуры Эвотора. Рецепт и добавки — из метаданных 6.7.")

        val products = try { loadProducts() } catch (e: Throwable) {
            addHint("Не удалось прочитать номенклатуру терминала: ${e.message ?: "ошибка"}")
            emptyList()
        }

        if (products.isEmpty()) {
            addHint("На терминале пока нет доступных товаров. Сначала выполните синхронизацию номенклатуры в Эвотор Cloud.")
            return
        }

        products.forEach { item ->
            val button = Button(this).apply {
                text = item.name
                textSize = 16f
                isAllCaps = false
                setOnClickListener { showProduct(item.uuid) }
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            }
            root.addView(button)
        }
    }

    private data class CatalogItem(val uuid: String, val name: String)

    private fun loadProducts(): List<CatalogItem> {
        val result = mutableListOf<CatalogItem>()
        contentResolver.query(ProductTable.URI, null, null, null, null)?.use { cursor ->
            val uuidColumn = findColumn(cursor, "UUID", "uuid")
            val nameColumn = findColumn(cursor, ProductTable.ROW_NAME, "NAME", "name")
            val groupColumn = findColumn(cursor, "IS_GROUP", "is_group")
            if (uuidColumn < 0 || nameColumn < 0) return@use
            while (cursor.moveToNext()) {
                val uuid = cursor.getString(uuidColumn)?.trim().orEmpty()
                val name = cursor.getString(nameColumn)?.trim().orEmpty()
                val isGroup = groupColumn >= 0 && (cursor.getString(groupColumn) == "1" || cursor.getString(groupColumn).equals("true", true))
                if (uuid.isNotEmpty() && name.isNotEmpty() && !isGroup) result.add(CatalogItem(uuid, name))
            }
        }
        return result.distinctBy { it.uuid }.sortedBy { it.name.lowercase() }
    }

    private fun showProduct(uuid: String) {
        val product = InventoryApi.getProductByUuid(this, uuid) as? ProductItem.Product
        if (product == null) {
            Toast.makeText(this, "Товар не найден в локальном inventory Эвотора", Toast.LENGTH_SHORT).show()
            return
        }

        val metadata = readSixthCupMetadata(uuid)
        root.removeAllViews()
        addTitle(product.name)
        addHint("Цена: ${product.price} ₽")
        addHint("Единица: ${product.measure.name}")

        val recipe = metadata?.optString("recipe", "").orEmpty()
        if (recipe.isNotBlank()) {
            addSection("Рецепт", recipe)
        }

        val toppings = metadata?.optJSONArray("toppings")
        if (toppings != null && toppings.length() > 0) {
            val names = buildString {
                for (i in 0 until toppings.length()) {
                    val t = toppings.optJSONObject(i)
                    if (t != null) {
                        if (isNotEmpty()) append(", ")
                        append(t.optString("name", "Добавка"))
                    }
                }
            }
            addSection("Доступные добавки", names)
        }

        val flags = mutableListOf<String>()
        if (metadata?.optBoolean("countsAsCup", false) == true) flags += "учитывается как стакан"
        if (metadata?.optBoolean("freeEligible", false) == true) flags += "может быть бесплатным по loyalty"
        if (flags.isNotEmpty()) addHint(flags.joinToString(" · "))

        val add = Button(this).apply {
            text = "Добавить в чек Эвотора"
            isAllCaps = false
            setOnClickListener { returnPosition(product) }
        }
        root.addView(add, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val back = Button(this).apply {
            text = "Назад"
            isAllCaps = false
            setOnClickListener { renderCatalog() }
        }
        root.addView(back, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
    }

    private fun returnPosition(product: ProductItem.Product) {
        val position = Position.Builder.newInstance(
            UUID.randomUUID().toString(),
            product.uuid,
            product.name,
            Measure(product.measure.name, 0, 0),
            product.price,
            BigDecimal.ONE,
        )
            .setTaxNumber(product.taxNumber)
            .setSettlementMethod(SettlementMethod.FullSettlement())
            .build()

        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(EXTRA_POSITION, position)
            putExtra(EXTRA_OPERATION_TYPE, operationTypeOrdinal)
        })
        finish()
    }

    private fun readSixthCupMetadata(productUuid: String): JSONObject? {
        val extra = InventoryApi.getProductExtras(this, productUuid)
            .firstOrNull { it.name == "sixthcup" || !it.data.isNullOrBlank() }
            ?: return null
        val raw = extra.data.orEmpty()
        if (raw.isBlank()) return null
        return try { JSONObject(raw) } catch (_: Throwable) { null }
    }

    private fun findColumn(cursor: Cursor, vararg names: String): Int {
        for (name in names) {
            val idx = cursor.getColumnIndex(name)
            if (idx >= 0) return idx
        }
        return -1
    }

    private fun addTitle(value: String) {
        root.addView(TextView(this).apply {
            text = value
            textSize = 23f
            setPadding(0, dp(4), 0, dp(8))
        })
    }

    private fun addHint(value: String) {
        root.addView(TextView(this).apply {
            text = value
            textSize = 14f
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun addSection(title: String, body: String) {
        root.addView(TextView(this).apply {
            text = "$title:\n$body"
            textSize = 15f
            setPadding(0, dp(8), 0, dp(12))
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
