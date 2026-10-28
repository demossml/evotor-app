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

    data class Header(val title: String, val aside: String = "") : CatalogRow() {
        override val key: String get() = "h:" + title
    }

    data class Item(val p: CatalogProduct, val meta: String = "") : CatalogRow() {
        override val key: String get() = "p:" + p.uuid
    }

    /** Готовая view хоста (строка поиска, чипы категорий): живёт в списке и скроллится вместе с ним, как `.sale-scroll`. */
    data class Host(val id: String, val view: View, val topDp: Int = 0) : CatalogRow() {
        override val key: String get() = "host:" + id
    }

    /** `.row-head` меню: «Всё меню» + «N позиций». */
    data class MenuHead(val title: String, val count: String) : CatalogRow() {
        override val key: String get() = "menuhead"
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
    /** Количество на плитке (сумма по размерам); по умолчанию — по uuid плитки. */
    val qtyOf: (CatalogProduct) -> Int = { Cart.qty(it.uuid) },
    /**
     * Бейдж льготы для плитки: «ПОДАРОК», если позиция попадает под выбранную льготу.
     * Каталог при этом не фильтруется — помечаем, но не скрываем.
     */
    val benefitBadge: (CatalogProduct) -> String? = { null },
)

private const val QTY = "qty"
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** `.tile-price` + `.tile-old`: цена чернилами, старая — мелко, серая, зачёркнутая. */
internal fun priceText(p: CatalogProduct, meta: String? = null): CharSequence {
    val now: String = UiKit.rub(p.priceRub)
    val old = p.oldPriceRub?.takeIf { it > p.priceRub }
    val sb = SpannableStringBuilder(now)
    if (old != null) {
        sb.append(" ")
        val st = sb.length
        sb.append(UiKit.rub(old))
        sb.setSpan(StrikethroughSpan(), st, sb.length, 0)
        sb.setSpan(ForegroundColorSpan(UiKit.MUTED), st, sb.length, 0)
        sb.setSpan(android.text.style.AbsoluteSizeSpan(10, true), st, sb.length, 0)
    }
    if (!meta.isNullOrEmpty()) {
        val st = sb.length
        sb.append("  ·  ")
        sb.append(meta)
        sb.setSpan(ForegroundColorSpan(UiKit.MUTED), st, sb.length, 0)
    }
    return sb
}

private fun qtyPill(ctx: Context, density: Float): TextView = TextView(ctx).apply {
    textSize = 13f
    setOnest(true)
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

/** Основной вертикальный список: поиск → фокус → чипы → «Всё меню» → плитки (как `.sale-scroll` в index). */
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
        is CatalogRow.Host -> 5
        is CatalogRow.MenuHead -> 6
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        0 -> FocusRowVH(parent.context, cb)
        1 -> HeaderVH(parent.context)
        2 -> ProductVH(parent.context, cb)
        3 -> SkeletonVH(parent.context)
        5 -> HostVH(parent.context)
        6 -> MenuHeadVH(parent.context)
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
            is ProductVH -> if (qtyOnly) h.bindQty() else h.bind((row as CatalogRow.Item).p, row.meta)
            is StateVH -> h.bind(row as CatalogRow.State)
            is HostVH -> if (!qtyOnly) h.bind(row as CatalogRow.Host)
            is MenuHeadVH -> if (!qtyOnly) h.bind(row as CatalogRow.MenuHead)
            else -> {}
        }
    }

    override fun onViewRecycled(h: RecyclerView.ViewHolder) {
        if (h is ProductVH) h.release()
        if (h is HostVH) h.unhost()
        super.onViewRecycled(h)
    }

    /** Количества в корзине изменились: обновляем только плашки «×N», без перебинда строк. */
    fun refreshQty() { if (itemCount > 0) notifyItemRangeChanged(0, itemCount, QTY) }
}

/**
 * Отступы сетки плиток: боковой отступ экрана `side` и зазор `gap` между колонками (`.menu-grid`).
 * Все плитки получают одинаковую суммарную вставку — ширина колонок равна, зазоры точные.
 */
class TileGridDecoration(private val cols: Int, private val sidePx: Int, private val gapPx: Int) : RecyclerView.ItemDecoration() {
    private val lefts = IntArray(cols)
    private val rights = IntArray(cols)
    init {
        val inset = (2 * sidePx + (cols - 1) * gapPx) / cols
        var left = sidePx
        for (c in 0 until cols) {
            lefts[c] = left
            rights[c] = inset - left
            left = gapPx - rights[c]
        }
    }
    override fun getItemOffsets(out: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val lp = view.layoutParams as? androidx.recyclerview.widget.GridLayoutManager.LayoutParams ?: return
        val pos = parent.getChildAdapterPosition(view)
        val adapter = parent.adapter as? CatalogAdapter ?: return
        val row = adapter.currentList.getOrNull(pos)
        if (row is CatalogRow.Item) {
            val c = lp.spanIndex.coerceIn(0, cols - 1)
            out.set(lefts[c], 0, rights[c].coerceAtLeast(0), gapPx)
        }
    }
}

// ───────────────────────── holders ─────────────────────────

/** Хост готовой view (поиск, чипы). */
private class HostVH(ctx: Context) : RecyclerView.ViewHolder(FrameLayout(ctx)) {
    private val box = itemView as FrameLayout
    private val d = ctx.resources.displayMetrics.density
    init { box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP) }
    fun bind(r: CatalogRow.Host) {
        (r.view.parent as? ViewGroup)?.removeView(r.view)
        box.removeAllViews()
        box.setPadding(0, (r.topDp * d).toInt(), 0, 0)
        box.addView(r.view, FrameLayout.LayoutParams(MATCH, WRAP))
    }
    fun unhost() { box.removeAllViews() }
}

/** `.row-head`: eyebrow «Сейчас берут» + пояснение справа. */
private class HeaderVH(ctx: Context) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
    private val title = TextView(ctx)
    private val aside = TextView(ctx)
    init {
        val m = Ds.metrics(ctx)
        val box = itemView as LinearLayout
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.BOTTOM
        box.setPadding(Ds.dp(ctx, m.sidePad), Ds.dp(ctx, 14), Ds.dp(ctx, m.sidePad), 0)
        box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        title.maxLines = 1
        title.textSize = Ds.sp(9.5f)
        title.setTextColor(UiKit.MUTED)
        title.letterSpacing = 0.12f
        title.weight(Fonts.STRONG)
        aside.textSize = Ds.sp(9.5f)
        aside.setTextColor(UiKit.MUTED)
        aside.maxLines = 1
        aside.gravity = Gravity.END
        aside.weight(Fonts.MEDIUM)
        box.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(aside, LinearLayout.LayoutParams(WRAP, WRAP))
    }
    fun bind(r: CatalogRow.Header) {
        title.text = r.title.uppercase()
        aside.text = r.aside
        aside.visibility = if (r.aside.isEmpty()) View.GONE else View.VISIBLE
    }
}

/** `.menu-head`: «Всё меню» 14.5/650 + «N позиций». */
private class MenuHeadVH(ctx: Context) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
    private val title = TextView(ctx)
    private val count = TextView(ctx)
    init {
        val m = Ds.metrics(ctx)
        val box = itemView as LinearLayout
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.BOTTOM
        box.setPadding(Ds.dp(ctx, m.sidePad), Ds.dp(ctx, 13), Ds.dp(ctx, m.sidePad), Ds.dp(ctx, 8))
        box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        title.textSize = 14.5f
        title.setTextColor(UiKit.INK)
        title.letterSpacing = -0.015f
        title.maxLines = 1
        title.weight(Fonts.STRONG)
        count.textSize = Ds.sp(9.5f)
        count.setTextColor(UiKit.MUTED)
        count.maxLines = 1
        box.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(count, LinearLayout.LayoutParams(WRAP, WRAP))
    }
    fun bind(r: CatalogRow.MenuHead) { title.text = r.title; count.text = r.count }
}

/** `.tile` 1:1: media (96/120/126) · flag · qty · − + · name/meta/price. */
private class ProductVH(ctx: Context, private val cb: CatalogCallbacks) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
    val thumb = ThumbView(ctx)
    private val thumb1 = ThumbView(ctx)
    private val thumb2 = ThumbView(ctx)
    private val comboRow = LinearLayout(ctx)
    private val name = TextView(ctx)
    private val meta = TextView(ctx)
    private val price = TextView(ctx)
    private val flag = TextView(ctx)
    private val qty: TextView
    private val minus: TextView
    private var item: CatalogProduct? = null
    private val thumbPx: Int
    init {
        val m = Ds.metrics(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = itemView as LinearLayout
        box.orientation = LinearLayout.VERTICAL
        box.background = Ds.rounded(ctx, UiKit.WHITE, 18f, UiKit.LINE)
        box.clipToOutline = true
        box.isClickable = true
        box.isFocusable = true
        box.contentDescription = "Товар меню"
        box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)

        val photoBox = FrameLayout(ctx).apply { background = Ds.mediaGradient() }
        thumb.setCorner(0)
        thumb.setContain(m.imgW, m.imgH)
        photoBox.addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH))

        // Комбо: два рисунка и «+» между ними (`.combo-pics`).
        comboRow.orientation = LinearLayout.HORIZONTAL
        comboRow.gravity = Gravity.CENTER
        comboRow.visibility = View.GONE
        thumb1.setCorner(0); thumb1.setContain(62, 50)
        thumb2.setCorner(0); thumb2.setContain(62, 50)
        val plusSign = TextView(ctx).apply {
            text = "+"; textSize = 13f; setTextColor(UiKit.MUTED); weight(Fonts.MEDIUM)
            setPadding(dp(1), 0, dp(1), 0)
        }
        comboRow.addView(thumb1, LinearLayout.LayoutParams(dp(62), dp(50)))
        comboRow.addView(plusSign)
        comboRow.addView(thumb2, LinearLayout.LayoutParams(dp(62), dp(50)))
        photoBox.addView(comboRow, FrameLayout.LayoutParams(MATCH, MATCH))

        val photoH = dp(m.mediaH)
        box.addView(photoBox, LinearLayout.LayoutParams(MATCH, photoH))
        thumbPx = (m.imgW * 2 * d).toInt().coerceIn(200, 480)

        // .tile-flag
        flag.textSize = Ds.sp(7f)
        flag.weight(Fonts.STRONG)
        flag.letterSpacing = 0.09f
        flag.setTextColor(UiKit.INK_2)
        flag.setPadding(dp(6), dp(3), dp(6), dp(3))
        flag.background = Ds.rounded(ctx, 0xE0FFFFFF.toInt(), 5f)
        flag.visibility = View.GONE
        photoBox.addView(flag, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(7); leftMargin = dp(7)
        })

        // .tile-qty
        qty = TextView(ctx).apply {
            textSize = Ds.sp(9f)
            weight(Fonts.STRONG)
            setTextColor(UiKit.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
            minWidth = dp(25)
            background = Ds.rounded(ctx, UiKit.INK, 6f)
            visibility = View.GONE
            elevation = 2f * d
        }
        photoBox.addView(qty, FrameLayout.LayoutParams(WRAP, dp(19)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(7); rightMargin = dp(7)
        })

        // .tile-controls: 30×30, gap 4
        fun ctl(label: String, plus: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            textSize = 16f
            includeFontPadding = false
            gravity = Gravity.CENTER
            weight(Fonts.MEDIUM)
            background = if (plus) Ds.rounded(ctx, UiKit.INK, 15f, UiKit.INK)
            else Ds.rounded(ctx, 0xF5FFFFFF.toInt(), 15f, 0xF2E7DFD3.toInt())
            setTextColor(if (plus) UiKit.WHITE else UiKit.INK)
            elevation = 2f * d
            setOnClickListener { onClick() }
        }
        minus = ctl("−", false) { item?.let(cb.onDec) }
        val plus = ctl("+", true) { item?.let(cb.onInc) }
        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(minus, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(4) })
        controls.addView(plus, LinearLayout.LayoutParams(dp(30), dp(30)))
        photoBox.addView(controls, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(7); rightMargin = dp(7)
        })

        // .tile-body: padding 8 10 9
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(9))
        }
        name.textSize = m.nameSp
        name.weight(Fonts.SEMIBOLD)
        name.letterSpacing = -0.01f
        name.setTextColor(UiKit.INK)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        body.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
        meta.textSize = Ds.sp(m.metaSp)
        meta.weight(Fonts.REGULAR)
        meta.setTextColor(UiKit.MUTED)
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        body.addView(meta, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(1) })
        price.textSize = m.priceSp
        price.weight(Fonts.STRONG)
        price.letterSpacing = -0.015f
        price.setTextColor(UiKit.INK)
        price.maxLines = 1
        price.ellipsize = TextUtils.TruncateAt.END
        body.addView(price, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        box.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))

        box.setOnClickListener { item?.let(cb.onTap) }
        box.setOnLongClickListener { item?.let(cb.onInfo); true }
        Ds.shadowS(box)
    }

    fun bind(p: CatalogProduct, metaText: String) {
        item = p
        name.text = p.name
        meta.text = metaText
        meta.visibility = if (metaText.isEmpty()) View.GONE else View.VISIBLE
        price.text = priceText(p)
        // Бейдж льготы важнее маркетингового: он объясняет, почему позиция станет дешевле.
        val benefit = cb.benefitBadge(p)
        when {
            benefit != null -> { flag.text = benefit; flag.visibility = View.VISIBLE }
            p.isCombo -> { flag.text = "КОМБО"; flag.visibility = View.VISIBLE }
            p.featured -> { flag.text = "ХИТ"; flag.visibility = View.VISIBLE }
            else -> flag.visibility = View.GONE
        }
        if (p.isCombo) {
            thumb.visibility = View.GONE
            comboRow.visibility = View.VISIBLE
            thumb1.bind(p.name, p.imageUrl, 200)
            thumb2.bind(p.name, p.imageUrl2, 200)
        } else {
            thumb.visibility = View.VISIBLE
            comboRow.visibility = View.GONE
            thumb.bind(p.name, p.imageUrl, thumbPx)
        }
        bindQty()
    }

    fun release() { thumb.release(); thumb1.release(); thumb2.release() }

    fun bindQty() {
        val it = item ?: return
        val n = cb.qtyOf(it)
        if (n > 0) { qty.text = "×$n"; qty.visibility = View.VISIBLE } else qty.visibility = View.GONE
        minus.visibility = if (n > 0 && !it.isCombo) View.VISIBLE else View.GONE
    }
}

private class FocusRowVH(ctx: Context, cb: CatalogCallbacks) : RecyclerView.ViewHolder(RecyclerView(ctx)) {
    private val rv = itemView as RecyclerView
    private val adapter = FocusAdapter(cb)
    init {
        val m = Ds.metrics(ctx)
        val d = ctx.resources.displayMetrics.density
        rv.layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        rv.adapter = adapter
        rv.overScrollMode = View.OVER_SCROLL_NEVER
        rv.isHorizontalScrollBarEnabled = false
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.setPadding((m.sidePad * d).toInt(), (8 * d).toInt(), (m.sidePad * d).toInt(), (3 * d).toInt())
        rv.setItemViewCacheSize(4)
        rv.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
    }
    fun bind(r: CatalogRow.Focus) { adapter.submitList(r.items) }
    fun refreshQty() { adapter.refreshQty() }
}

private class FocusAdapter(private val cb: CatalogCallbacks) : ListAdapter<CatalogProduct, FocusCardVH>(PRODUCT_DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = FocusCardVH(parent.context, cb)
    override fun onBindViewHolder(h: FocusCardVH, position: Int) = h.bind(getItem(position))
    override fun onViewRecycled(h: FocusCardVH) { h.thumb.release(); super.onViewRecycled(h) }
    fun refreshQty() {}
}

/** `.focus-card` 1:1: 158×74, img 53×58, name 11.5/600, price 11/600, badge, › справа сверху. */
private class FocusCardVH(ctx: Context, cb: CatalogCallbacks) : RecyclerView.ViewHolder(FrameLayout(ctx)) {
    val thumb = ThumbView(ctx)
    private val name = TextView(ctx)
    private val price = TextView(ctx)
    private val badge = TextView(ctx)
    private var item: CatalogProduct? = null
    init {
        val m = Ds.metrics(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = itemView as FrameLayout
        root.layoutParams = RecyclerView.LayoutParams(dp(m.focusW), dp(74)).apply { rightMargin = dp(8) }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(7), dp(8), dp(7))
            background = Ds.rounded(ctx, UiKit.WHITE, 16f, UiKit.LINE)
            isClickable = true
            isFocusable = true
        }
        Ds.shadowS(box)
        root.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))

        val imgBox = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFF2ECE1.toInt(), 0xFFECE3D6.toInt()))
                .apply { cornerRadius = 11 * d }
            clipToOutline = true
        }
        thumb.setCorner(0)
        thumb.setContain(58, 46)
        imgBox.addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(imgBox, LinearLayout.LayoutParams(dp(53), dp(58)))

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        name.textSize = 11.5f
        name.weight(Fonts.SEMIBOLD)
        name.letterSpacing = -0.005f
        name.setTextColor(UiKit.INK)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        textCol.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
        price.textSize = 11f
        price.weight(Fonts.SEMIBOLD)
        price.setTextColor(UiKit.INK_2)
        price.maxLines = 1
        textCol.addView(price, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        badge.textSize = Ds.sp(7f)
        badge.weight(Fonts.STRONG)
        badge.letterSpacing = 0.09f
        badge.setTextColor(UiKit.INK_2)
        badge.setPadding(dp(4), dp(2), dp(4), dp(2))
        badge.background = Ds.rounded(ctx, 0xFFF3EEE5.toInt(), 4f, UiKit.LINE)
        badge.visibility = View.GONE
        textCol.addView(badge, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        box.addView(textCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        val add = FrameLayout(ctx).apply { background = Ds.rounded(ctx, 0xD9FFFFFF.toInt(), 10f, UiKit.LINE) }
        add.addView(icon(ctx, ru.sixthcup.evotor.R.drawable.ic_chevron_sm, 10f, UiKit.MUTED),
            FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
        root.addView(add, FrameLayout.LayoutParams(dp(20), dp(20)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(6); rightMargin = dp(6)
        })

        box.setOnClickListener { item?.let(cb.onInc) }
        box.setOnLongClickListener { item?.let(cb.onInfo); true }
    }

    fun bind(p: CatalogProduct) {
        item = p
        name.text = p.name
        price.text = UiKit.rub(p.priceRub)
        val tag = when { p.isCombo -> "КОМБО"; p.featured -> "ХИТ"; else -> "" }
        badge.text = tag
        badge.visibility = if (tag.isEmpty()) View.GONE else View.VISIBLE
        thumb.bind(p.name, p.imageUrl, 160)
    }
}

/** Скелетон: серые блоки без анимации. Форма — плитка 2×N. */
private class SkeletonVH(ctx: Context) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
    init {
        val m = Ds.metrics(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = itemView as LinearLayout
        box.orientation = LinearLayout.VERTICAL
        box.background = Ds.rounded(ctx, UiKit.WHITE, 18f, UiKit.LINE)
        box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        box.addView(View(ctx).apply { setBackgroundColor(UiKit.SKELETON) }, LinearLayout.LayoutParams(MATCH, dp(m.mediaH)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(12)) }
        col.addView(View(ctx).apply { background = Ds.rounded(ctx, UiKit.SKELETON, 4f) }, LinearLayout.LayoutParams(dp(90), dp(12)))
        col.addView(View(ctx).apply { background = Ds.rounded(ctx, UiKit.SKELETON, 4f) }, LinearLayout.LayoutParams(dp(50), dp(12)).apply { topMargin = dp(8) })
        box.addView(col, LinearLayout.LayoutParams(MATCH, WRAP))
    }
}

/** `.empty-search`: логотип 56 с прозрачностью, заголовок 13/600, подсказка 11. */
private class StateVH(ctx: Context, private val cb: CatalogCallbacks) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
    private val msg = TextView(ctx)
    private val btn = Button(ctx)
    private val d = ctx.resources.displayMetrics.density
    private val logo = android.widget.ImageView(ctx)
    init {
        fun dp(v: Int) = (v * d).toInt()
        val m = Ds.metrics(ctx)
        val box = itemView as LinearLayout
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(dp(m.sidePad + 2), dp(34), dp(m.sidePad + 2), dp(34))
        box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        logo.setImageResource(ru.sixthcup.evotor.R.drawable.logo_67_mark)
        logo.alpha = 0.35f
        box.addView(logo, LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(8) })
        msg.textSize = 13f
        msg.gravity = Gravity.CENTER
        msg.setTextColor(UiKit.MUTED)
        box.addView(msg, LinearLayout.LayoutParams(MATCH, WRAP))
        btn.text = "Повторить"; btn.textSize = 12f; btn.transformationMethod = null
        btn.minHeight = dp(48); btn.minimumHeight = dp(48)
        btn.background = Ds.rounded(ctx, UiKit.BRAND, 14f)
        btn.setTextColor(UiKit.WHITE)
        btn.weight(Fonts.STRONG)
        btn.setOnClickListener { cb.onRetry() }
        box.addView(btn, LinearLayout.LayoutParams(dp(180), dp(48)).apply { topMargin = dp(12) })
    }
    fun bind(r: CatalogRow.State) {
        msg.text = r.message
        msg.setTextColor(if (r.warn) 0xFF6B4700.toInt() else UiKit.MUTED)
        btn.text = r.action ?: ""
        btn.visibility = if (r.action != null) View.VISIBLE else View.GONE
    }
}
