package ru.sixthcup.evotor.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    val onInc: (CatalogProduct) -> Unit = {},
    val onDec: (CatalogProduct) -> Unit = {},
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

/** Заголовки секций index: «Сейчас берут» = eyebrow, «Всё меню» = menu-title. */
private class HeaderVH(ctx: Context) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
    private val title = TextView(ctx)
    private val aside = TextView(ctx)
    init {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = itemView as LinearLayout
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.BOTTOM
        box.setPadding(0, dp(10), 0, dp(4))
        box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        title.setTypeface(null, Typeface.BOLD)
        title.maxLines = 1
        aside.textSize = 9.5f
        aside.setTextColor(UiKit.MUTED)
        aside.maxLines = 1
        aside.gravity = Gravity.END
        box.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(aside, LinearLayout.LayoutParams(WRAP, WRAP))
    }
    fun bind(r: CatalogRow.Header) {
        val t = r.title
        // index: focus section uses uppercase eyebrow «Сейчас берут»
        if (t.equals("Сейчас берут", ignoreCase = true) || t.equals("Фокус", ignoreCase = true)) {
            title.text = "СЕЙЧАС БЕРУТ"
            title.textSize = 9.5f
            title.setTextColor(UiKit.MUTED)
            title.letterSpacing = 0.12f
            aside.text = ""
            aside.visibility = View.GONE
        } else {
            title.text = if (t.equals("Меню", ignoreCase = true)) "Всё меню" else t
            title.textSize = 14.5f
            title.setTextColor(UiKit.INK)
            title.letterSpacing = -0.015f
            aside.visibility = View.VISIBLE
            aside.text = ""
        }
    }
}

private class ProductVH(ctx: Context, cb: CatalogCallbacks) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
    val thumb = ThumbView(ctx)
    private val name = TextView(ctx)
    private val meta = TextView(ctx)
    private val price = TextView(ctx)
    private val flag = TextView(ctx)
    private val qty: TextView
    private val plusBtn = TextView(ctx)
    private val minusBtn = TextView(ctx)
    private val controls: LinearLayout
    private var item: CatalogProduct? = null
    private val thumbPx: Int
    init {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = itemView as LinearLayout
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, 0, 0, 0)
        box.background = UiKit.rounded(d, UiKit.WHITE, 16, UiKit.LINE)
        box.isClickable = true
        box.isFocusable = true
        box.contentDescription = "Товар меню"
        box.elevation = 1f * d
        box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
            width = MATCH
            // menu-grid gap 9px → ~4.5 each side
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }

        // tile-media height 96, warm gradient
        val photoBox = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFF4EFE6.toInt(), 0xFFECE4D8.toInt())
            )
        }
        thumb.setCorner(0)
        photoBox.addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH))
        val photoH = dp(80)
        box.addView(photoBox, LinearLayout.LayoutParams(MATCH, photoH))
        thumbPx = (photoH * 2).coerceIn(200, 400)

        // tile-flag top-left
        flag.textSize = 7f
        flag.setTypeface(null, Typeface.BOLD)
        flag.setTextColor(UiKit.INK_2)
        flag.setPadding(dp(4), dp(2), dp(4), dp(2))
        flag.background = UiKit.rounded(d, 0xE0FFFFFF.toInt(), 5, null)
        flag.visibility = View.GONE
        photoBox.addView(flag, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(7); leftMargin = dp(7)
        })

        // tile-qty top-right (ink, not brand pill)
        qty = TextView(ctx).apply {
            textSize = 8.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(UiKit.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(5), 0, dp(5), 0)
            minWidth = dp(22)
            minHeight = dp(17)
            background = UiKit.rounded(d, UiKit.INK, 6, null)
            visibility = View.GONE
            elevation = 2f * d
        }
        photoBox.addView(qty, FrameLayout.LayoutParams(WRAP, dp(17)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(5); rightMargin = dp(5)
        })

        // tile-controls bottom-right of media
        controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun ctl(label: String, plus: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.NORMAL)
            background = if (plus) {
                UiKit.rounded(d, UiKit.INK, 15, UiKit.INK)
            } else {
                UiKit.rounded(d, 0xF5FFFFFF.toInt(), 15, UiKit.LINE)
            }
            setTextColor(if (plus) UiKit.WHITE else UiKit.INK)
            elevation = 2f * d
            setOnClickListener { onClick() }
        }
        minusBtn.apply {
            // filled below via ctl helper pattern
        }
        val minus = ctl("−", false) { item?.let(cb.onDec) }
        val plus = ctl("+", true) { item?.let(cb.onInc) }
        // store refs for visibility
        minusBtn.text = "−"
        plusBtn.text = "+"
        controls.addView(minus, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(3) })
        controls.addView(plus, LinearLayout.LayoutParams(dp(26), dp(26)))
        // keep field refs for bindQty visibility of minus
        minusBtn.tag = minus
        plusBtn.tag = plus
        photoBox.addView(controls, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(5); rightMargin = dp(5)
        })

        // tile-body
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(8))
        }
        name.textSize = 12f
        name.setTypeface(null, Typeface.BOLD)
        name.setTextColor(UiKit.INK)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        body.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
        meta.textSize = 8.5f
        meta.setTextColor(UiKit.MUTED)
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        body.addView(meta, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(1) })
        price.textSize = 13.5f
        price.setTypeface(null, Typeface.BOLD)
        price.setTextColor(UiKit.INK)
        price.maxLines = 1
        price.ellipsize = TextUtils.TruncateAt.END
        body.addView(price, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        box.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))

        box.setOnClickListener { item?.let(cb.onTap) }
        box.setOnLongClickListener { item?.let(cb.onInfo); true }
    }

    fun bind(p: CatalogProduct) {
        item = p
        name.text = p.name
        meta.text = when {
            p.isCombo -> "Комбо"
            !p.groupName.isNullOrBlank() -> p.groupName
            p.freeEligible -> "Напиток"
            else -> ""
        }
        meta.visibility = if (meta.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        price.text = priceText(p)
        // tile-flag only for real badges we know from data (combo / featured hit)
        when {
            p.isCombo -> { flag.text = "КОМБО"; flag.visibility = View.VISIBLE }
            p.featured -> { flag.text = "ХИТ"; flag.visibility = View.VISIBLE }
            else -> flag.visibility = View.GONE
        }
        thumb.bind(p.name, p.imageUrl, thumbPx)
        bindQty()
    }

    fun bindQty() {
        val uuid = item?.uuid ?: return
        val n = Cart.qty(uuid)
        if (n > 0) {
            qty.text = "×$n"
            qty.visibility = View.VISIBLE
        } else {
            qty.visibility = View.GONE
        }
        val minus = minusBtn.tag as? View
        minus?.visibility = if (n > 0) View.VISIBLE else View.GONE
    }
}

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

/** focus-card 1:1 index: 158×74, img 53×58, name 11.5, price 11, badge, + */
private class FocusCardVH(ctx: Context, cb: CatalogCallbacks) : RecyclerView.ViewHolder(FrameLayout(ctx)) {
    val thumb = ThumbView(ctx)
    private val name = TextView(ctx)
    private val price = TextView(ctx)
    private val badge = TextView(ctx)
    private val addBtn = TextView(ctx)
    private val qty: TextView
    private var item: CatalogProduct? = null
    init {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = itemView as FrameLayout
        root.layoutParams = RecyclerView.LayoutParams(dp(158), dp(74)).apply { rightMargin = dp(8) }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(7), dp(8), dp(7))
            background = UiKit.rounded(d, UiKit.WHITE, 16, UiKit.LINE)
            isClickable = true
            isFocusable = true
            elevation = 1f * d
        }
        root.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))

        val imgBox = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(0xFFF2ECE1.toInt())
                cornerRadius = 11 * d
            }
        }
        thumb.setCorner(11)
        imgBox.addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(imgBox, LinearLayout.LayoutParams(dp(53), dp(58)))

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }
        name.textSize = 12f
        name.setTypeface(null, Typeface.BOLD)
        name.setTextColor(UiKit.INK)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        textCol.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
        price.textSize = 11f
        price.setTypeface(null, Typeface.BOLD)
        price.setTextColor(UiKit.INK_2)
        price.maxLines = 1
        textCol.addView(price, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        badge.textSize = 7f
        badge.setTypeface(null, Typeface.BOLD)
        badge.setTextColor(UiKit.INK_2)
        badge.setPadding(dp(4), dp(2), dp(4), dp(2))
        badge.background = UiKit.rounded(d, 0xFFF3EEE5.toInt(), 4, UiKit.LINE)
        badge.visibility = View.GONE
        textCol.addView(badge, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
        box.addView(textCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        addBtn.text = "+"
        addBtn.textSize = 14f
        addBtn.gravity = Gravity.CENTER
        addBtn.setTextColor(UiKit.MUTED)
        addBtn.background = UiKit.rounded(d, 0xD9FFFFFF.toInt(), 10, UiKit.LINE)
        root.addView(addBtn, FrameLayout.LayoutParams(dp(20), dp(20)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(6)
            rightMargin = dp(6)
        })

        qty = qtyPill(ctx, d)
        root.addView(qty, FrameLayout.LayoutParams(WRAP, dp(22)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(6)
            rightMargin = dp(6)
        })

        box.setOnClickListener { item?.let(cb.onTap) }
        addBtn.setOnClickListener { item?.let(cb.onInc) }
        box.setOnLongClickListener { item?.let(cb.onInfo); true }
    }

    fun bind(p: CatalogProduct) {
        item = p
        name.text = p.name
        price.text = UiKit.rub(p.priceRub)
        badge.visibility = if (p.isCombo) View.VISIBLE else View.GONE
        badge.text = if (p.isCombo) "КОМБО" else ""
        thumb.bind(p.name, p.imageUrl, 160)
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
        box.background = UiKit.rounded(d, UiKit.WHITE, 16, UiKit.LINE)
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
