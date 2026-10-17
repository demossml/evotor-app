package ru.sixthcup.evotor.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CatalogProduct

/** Строки списка каталога. key — стабильная идентичность для DiffUtil (без notifyDataSetChanged). */
sealed class CatalogRow {
    abstract val key: String

    data class Focus(val items: List<CatalogProduct>) : CatalogRow() {
        override val key: String get() = "focus"
    }

    data class Header(val title: String) : CatalogRow() {
        override val key: String get() = "h:" + title
    }

    data class Item(val p: CatalogProduct) : CatalogRow() {
        override val key: String get() = "p:" + p.uuid
    }

    data class Skeleton(val i: Int) : CatalogRow() {
        override val key: String get() = "s:" + i
    }

    /** Пустое/ошибочное состояние на всю ширину; action != null → кнопка. */
    data class State(val id: String, val message: String, val warn: Boolean, val action: String?) : CatalogRow() {
        override val key: String get() = "st:" + id
    }
}

class CatalogCallbacks(
    val onTap: (CatalogProduct) -> Unit,
    val onInfo: (CatalogProduct) -> Unit,
    val onRetry: () -> Unit,
)

private const val QTY = "qty"
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Цена + (если есть #old=) зачёркнутая старая; красная — только цена по акции. */
internal fun priceText(p: CatalogProduct, meta: String? = null): CharSequence {
    val now: String = UiKit.rub(p.priceRub)
    val old = p.oldPriceRub?.takeIf { it > p.priceRub }
    val sb = SpannableStringBuilder(now)
    if (old != null) {
        sb.setSpan(ForegroundColorSpan(UiKit.PROMO), 0, now.length, 0)
        sb.append("  ")
        val st = sb.length
        val oldText: String = UiKit.rub(old)
        sb.append(oldText)
        sb.setSpan(StrikethroughSpan(), st, sb.length, 0)
        sb.setSpan(ForegroundColorSpan(UiKit.HINT), st, sb.length, 0)
    }
    if (!meta.isNullOrEmpty()) {
        val st = sb.length
        sb.append("  ·  ")
        sb.append(meta)
        sb.setSpan(ForegroundColorSpan(UiKit.HINT), st, sb.length, 0)
    }
    return sb
}

private fun qtyPill(ctx: Context, density: Float): TextView = TextView(ctx).apply {
    textSize = 13f
    setTypeface(null, Typeface.BOLD)
    setTextColor(UiKit.WHITE)
    gravity = Gravity.CENTER
    minWidth = (28 * density).toInt()
    setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
    background = UiKit.rounded(density, UiKit.BRAND, 14, null)
    visibility = View.GONE
}

private fun applyQty(v: TextView, uuid: String) {
    val n = Cart.qty(uuid)
    if (n > 0) { v.text = "×$n"; v.visibility = View.VISIBLE } else v.visibility = View.GONE
}

private val PRODUCT_DIFF = object : DiffUtil.ItemCallback<CatalogProduct>() {
    override fun areItemsTheSame(a: CatalogProduct, b: CatalogProduct) = a.uuid == b.uuid
    override fun areContentsTheSame(a: CatalogProduct, b: CatalogProduct) = a == b
}

/** Основной вертикальный список: Фокус (горизонтальная карусель) → заголовок → строки товаров. */
class CatalogAdapter(private val cb: CatalogCallbacks) : ListAdapter<CatalogRow, RecyclerView.ViewHolder>(
    object : DiffUtil.ItemCallback<CatalogRow>() {
        override fun areItemsTheSame(a: CatalogRow, b: CatalogRow) = a.key == b.key
        override fun areContentsTheSame(a: CatalogRow, b: CatalogRow) = a == b
    }
) {
    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is CatalogRow.Focus -> 0
        is CatalogRow.Header -> 1
        is CatalogRow.Item -> 2
        is CatalogRow.Skeleton -> 3
        is CatalogRow.State -> 4
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        0 -> FocusRowVH(parent.context, cb)
        1 -> HeaderVH(parent.context)
        2 -> ProductVH(parent.context, cb)
        3 -> SkeletonVH(parent.context)
        else -> StateVH(parent.context, cb)
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(h, position, mutableListOf())
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val row = getItem(position)
        val qtyOnly = payloads.contains(QTY)
        when (h) {
            is FocusRowVH -> if (qtyOnly) h.refreshQty() else h.bind(row as CatalogRow.Focus)
            is HeaderVH -> h.bind(row as CatalogRow.Header)
            is ProductVH -> if (qtyOnly) h.bindQty() else h.bind((row as CatalogRow.Item).p)
            is StateVH -> h.bind(row as CatalogRow.State)
            else -> {}
        }
    }

    override fun onViewRecycled(h: RecyclerView.ViewHolder) {
        if (h is ProductVH) h.thumb.release()
        super.onViewRecycled(h)
    }

    /** Количества в корзине изменились: обновляем только плашки «×N», без перебинда строк. */
    fun refreshQty() { if (itemCount > 0) notifyItemRangeChanged(0, itemCount, QTY) }
}

// ───────────────────────── holders ─────────────────────────

private class HeaderVH(ctx: Context) : RecyclerView.ViewHolder(TextView(ctx)) {
    private val tv = itemView as TextView
    init {
        val d = ctx.resources.displayMetrics.density
        tv.textSize = 15f; tv.setTypeface(null, Typeface.BOLD); tv.setTextColor(UiKit.INK)
        tv.setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        tv.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
    }
    fun bind(r: CatalogRow.Header) { tv.text = r.title }
}

private class ProductVH(ctx: Context, cb: CatalogCallbacks) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
    val thumb = ThumbView(ctx)
    private val name = TextView(ctx)
    private val meta = TextView(ctx)
    private val qty: TextView
    private val info = TextView(ctx)
    private var item: CatalogProduct? = null
    private val thumbPx: Int
    init {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = itemView as LinearLayout
        // PWA-like tile: photo on top, name + price below (2-column grid parent)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(8), dp(8), dp(8))
        box.background = UiKit.rounded(d, UiKit.WHITE, 20, UiKit.LINE)
        box.isClickable = true; box.isFocusable = true
        box.contentDescription = "Товар меню"
        box.layoutParams = RecyclerView.LayoutParams(MATCH, dp(208)).apply {
            width = MATCH
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }

        val photoBox = FrameLayout(ctx)
        thumb.setCorner(16)
        photoBox.addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH))
        // square photo via 1:1 — fixed height ~ half column on ST5
        val photoH = dp(100)
        box.addView(photoBox, LinearLayout.LayoutParams(MATCH, photoH))
        thumbPx = (photoH * 2).coerceIn(240, 480)

        qty = qtyPill(ctx, d)
        photoBox.addView(qty, FrameLayout.LayoutParams(WRAP, dp(28)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(6); rightMargin = dp(6)
        })

        name.textSize = 14f; name.setTypeface(null, Typeface.BOLD); name.setTextColor(UiKit.INK)
        name.maxLines = 2; name.ellipsize = TextUtils.TruncateAt.END
        name.setPadding(0, dp(8), 0, 0)
        box.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))

        meta.textSize = 16f; meta.setTypeface(null, Typeface.BOLD); meta.setTextColor(UiKit.BRAND)
        meta.maxLines = 1; meta.ellipsize = TextUtils.TruncateAt.END
        box.addView(meta, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })

        info.text = "i"; info.textSize = 12f; info.gravity = Gravity.CENTER
        info.setTextColor(UiKit.BRAND); info.setTypeface(null, Typeface.BOLD)
        info.background = UiKit.rounded(d, UiKit.WHITE, 14, UiKit.BRAND)
        info.visibility = View.INVISIBLE
        box.addView(info, LinearLayout.LayoutParams(dp(28), dp(24)).apply { topMargin = dp(4) })

        box.setOnClickListener { item?.let(cb.onTap) }
        box.setOnLongClickListener { item?.let(cb.onInfo); true }
        info.setOnClickListener { item?.let(cb.onInfo) }
    }

    fun bind(p: CatalogProduct) {
        item = p
        name.text = p.name
        val m = when { p.isCombo -> "комбо"; p.freeEligible -> "стакан"; else -> null }
        meta.text = priceText(p, m)
        thumb.bind(p.name, p.imageUrl, thumbPx)
        info.visibility = if (p.recipe.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
        info.contentDescription = "Рецепт: " + p.name
        bindQty()
    }

    fun bindQty() { item?.let { applyQty(qty, it.uuid) } }
}

/** Строка с горизонтальной каруселью «Фокус»; внутри свой ListAdapter + DiffUtil. */
private class FocusRowVH(ctx: Context, cb: CatalogCallbacks) : RecyclerView.ViewHolder(RecyclerView(ctx)) {
    private val rv = itemView as RecyclerView
    private val adapter = FocusAdapter(cb)
    init {
        val d = ctx.resources.displayMetrics.density
        rv.layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        rv.adapter = adapter
        rv.overScrollMode = View.OVER_SCROLL_NEVER
        rv.isHorizontalScrollBarEnabled = false
        rv.itemAnimator = null
        rv.setItemViewCacheSize(4)
        rv.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { bottomMargin = (8 * d).toInt() }
    }
    fun bind(r: CatalogRow.Focus) { adapter.submitList(r.items) }
    fun refreshQty() { adapter.refreshQty() }
}

private class FocusAdapter(private val cb: CatalogCallbacks) : ListAdapter<CatalogProduct, FocusCardVH>(PRODUCT_DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = FocusCardVH(parent.context, cb)
    override fun onBindViewHolder(h: FocusCardVH, position: Int) = h.bind(getItem(position))
    override fun onBindViewHolder(h: FocusCardVH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(QTY)) h.bindQty() else h.bind(getItem(position))
    }
    override fun onViewRecycled(h: FocusCardVH) { h.thumb.release(); super.onViewRecycled(h) }
    fun refreshQty() { if (itemCount > 0) notifyItemRangeChanged(0, itemCount, QTY) }
}

/** Карточка «Фокус»: фото 120×88 dp сверху (запрос 288 px), название ≤2 строк, цена (+ старая). */
private class FocusCardVH(ctx: Context, cb: CatalogCallbacks) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
    val thumb = ThumbView(ctx)
    private val name = TextView(ctx)
    private val price = TextView(ctx)
    private val combo = TextView(ctx)
    private val qty: TextView
    private var item: CatalogProduct? = null
    init {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = itemView as LinearLayout
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(8), dp(8), dp(8))
        box.background = UiKit.rounded(d, UiKit.WHITE, 20, UiKit.LINE)
        box.isClickable = true; box.isFocusable = true
        box.layoutParams = RecyclerView.LayoutParams(dp(144), WRAP).apply { rightMargin = dp(8) }

        thumb.setCorner(12)
        val photo = FrameLayout(ctx)
        photo.addView(thumb, FrameLayout.LayoutParams(MATCH, dp(88)))
        combo.text = "Комбо"; combo.textSize = 11f; combo.setTypeface(null, Typeface.BOLD); combo.setTextColor(UiKit.WHITE)
        combo.setPadding(dp(6), dp(1), dp(6), dp(1))
        combo.background = UiKit.rounded(d, UiKit.BRAND, 8, null)
        combo.visibility = View.GONE
        photo.addView(combo, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(4); bottomMargin = dp(4) })
        qty = qtyPill(ctx, d)
        photo.addView(qty, FrameLayout.LayoutParams(WRAP, dp(28), Gravity.TOP or Gravity.END).apply { topMargin = dp(4); rightMargin = dp(4) })
        box.addView(photo, LinearLayout.LayoutParams(MATCH, WRAP))

        name.textSize = 14f; name.setTypeface(null, Typeface.BOLD); name.setTextColor(UiKit.INK)
        name.minLines = 2; name.maxLines = 2; name.ellipsize = TextUtils.TruncateAt.END
        box.addView(name, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        price.textSize = 15f; price.setTypeface(null, Typeface.BOLD); price.setTextColor(UiKit.BRAND)
        price.maxLines = 1; price.ellipsize = TextUtils.TruncateAt.END
        box.addView(price, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })

        box.setOnClickListener { item?.let(cb.onTap) }
        box.setOnLongClickListener { item?.let(cb.onInfo); true }
    }

    fun bind(p: CatalogProduct) {
        item = p
        name.text = p.name
        price.text = priceText(p)
        combo.visibility = if (p.isCombo) View.VISIBLE else View.GONE
        thumb.bind(p.name, p.imageUrl, 288)
        bindQty()
    }

    fun bindQty() { item?.let { applyQty(qty, it.uuid) } }
}

/** Скелетон строки: серые блоки без анимации (анимация на ST5 только греет GPU). */
private class SkeletonVH(ctx: Context) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
    init {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = itemView as LinearLayout
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(8), dp(6), dp(8), dp(6))
        box.background = UiKit.rounded(d, UiKit.WHITE, 14, UiKit.LINE)
        box.layoutParams = RecyclerView.LayoutParams(MATCH, dp(64)).apply { bottomMargin = dp(6) }
        box.addView(View(ctx).apply { background = UiKit.rounded(d, UiKit.SKELETON, 26, null) }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(10) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(View(ctx).apply { background = UiKit.rounded(d, UiKit.SKELETON, 4, null) }, LinearLayout.LayoutParams(dp(150), dp(12)))
        col.addView(View(ctx).apply { background = UiKit.rounded(d, UiKit.SKELETON, 4, null) }, LinearLayout.LayoutParams(dp(70), dp(12)).apply { topMargin = dp(8) })
        box.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
}

private class StateVH(ctx: Context, private val cb: CatalogCallbacks) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
    private val msg = TextView(ctx)
    private val btn = Button(ctx)
    private val d = ctx.resources.displayMetrics.density
    init {
        fun dp(v: Int) = (v * d).toInt()
        val box = itemView as LinearLayout
        box.orientation = LinearLayout.VERTICAL
        box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        msg.textSize = 15f
        msg.setPadding(dp(12), dp(10), dp(12), dp(10))
        box.addView(msg, LinearLayout.LayoutParams(MATCH, WRAP))
        btn.text = "Повторить"; btn.textSize = 15f; btn.transformationMethod = null
        btn.minHeight = dp(48); btn.minimumHeight = dp(48)
        btn.background = UiKit.rounded(d, UiKit.BRAND, 12, UiKit.BRAND)
        btn.setTextColor(UiKit.WHITE)
        btn.setOnClickListener { cb.onRetry() }
        box.addView(btn, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(8) })
    }
    fun bind(r: CatalogRow.State) {
        msg.text = r.message
        msg.setTextColor(if (r.warn) 0xFF6B4700.toInt() else UiKit.HINT)
        msg.background = UiKit.rounded(d, if (r.warn) 0xFFFFF4D6.toInt() else UiKit.SOFT, 12, null)
        btn.text = r.action ?: ""
        btn.visibility = if (r.action != null) View.VISIBLE else View.GONE
    }
}
