package ru.sixthcup.evotor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Предзаказ, подтянутый в текущий чек: нужен, чтобы после успешной продажи вызвать `markPaid`
 * и не потерять связь при перезапуске процесса (чек Эвотор открывается в другом приложении).
 *
 * Источник правды — этот объект + SharedPreferences; `pull_order_id` из 160 больше не читается.
 */
object PreorderPullSession {
    private const val PREF = "preorder_pull"
    private const val KEY_ID = "order_id"
    private const val KEY_CODE = "short_code"
    private const val KEY_ITEMS = "items"
    private const val KEY_BENEFIT_KIND = "benefit_kind"
    private const val KEY_BENEFIT_TITLE = "benefit_title"
    private const val KEY_BENEFIT_RUB = "benefit_rub"

    data class Item(val productUuid: String, val name: String, val quantity: Int)

    private var ctx: Context? = null
    private var orderId: String? = null
    private var shortCode: String? = null
    private var items: List<Item> = emptyList()
    private var benefit: OrderBenefit? = null

    fun attach(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        restore()
    }

    fun orderId(): String? = orderId?.takeIf { it.isNotBlank() }

    fun shortCode(): String? = shortCode?.takeIf { it.isNotBlank() }

    fun items(): List<Item> = items

    /** Льгота заказа, применённая backend'ом; `null` — карточный путь как раньше. */
    fun benefit(): OrderBenefit? = benefit

    fun active(): Boolean = orderId() != null

    fun set(orderId: String, shortCode: String?, items: List<Item>, benefit: OrderBenefit? = null) {
        this.orderId = orderId
        this.shortCode = shortCode
        this.items = items
        this.benefit = benefit
        save()
    }

    fun clear() {
        orderId = null
        shortCode = null
        items = emptyList()
        benefit = null
        val c = ctx ?: return
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Throwable) {
        }
    }

    private fun save() {
        val c = ctx ?: return
        try {
            val arr = JSONArray()
            items.forEach { i ->
                arr.put(
                    JSONObject()
                        .put("u", i.productUuid)
                        .put("n", i.name)
                        .put("q", i.quantity),
                )
            }
            val editor = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_ID, orderId)
                .putString(KEY_CODE, shortCode)
                .putString(KEY_ITEMS, arr.toString())
            val b = benefit
            if (b == null) {
                editor.remove(KEY_BENEFIT_KIND).remove(KEY_BENEFIT_TITLE).remove(KEY_BENEFIT_RUB)
            } else {
                editor.putString(KEY_BENEFIT_KIND, b.kind)
                    .putString(KEY_BENEFIT_TITLE, b.title)
                    .putString(KEY_BENEFIT_RUB, b.amountRub.toPlainString())
            }
            editor.apply()
        } catch (_: Throwable) {
        }
    }

    private fun restore() {
        val c = ctx ?: return
        try {
            val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            orderId = p.getString(KEY_ID, null)?.takeIf { it.isNotBlank() }
            shortCode = p.getString(KEY_CODE, null)?.takeIf { it.isNotBlank() }
            val raw = p.getString(KEY_ITEMS, null)
            items = if (raw.isNullOrBlank()) emptyList() else {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val uuid = o.optString("u")
                    if (uuid.isBlank()) null else Item(uuid, o.optString("n"), o.optInt("q", 1).coerceAtLeast(1))
                }
            }
            benefit = OrderBenefit.restore(
                p.getString(KEY_BENEFIT_KIND, null),
                p.getString(KEY_BENEFIT_TITLE, null),
                p.getString(KEY_BENEFIT_RUB, null),
            )
            if (orderId.isNullOrBlank()) {
                items = emptyList()
                benefit = null
            }
        } catch (_: Throwable) {
            orderId = null; shortCode = null; items = emptyList(); benefit = null
        }
    }
}
