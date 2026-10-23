package ru.sixthcup.evotor.ui

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.DrinkVariants
import java.math.BigDecimal

/**
 * Блок опций item-sheet / combo-sheet — 1:1 с `index`:
 * группы «Объём / Молоко / Сироп / Сахар / Добавки», выбранная опция обведена,
 * у недоступной подпись «нет в кассе».
 *
 * Цена опции — `PRICE_OUT` реального товара Эвотор. Дельт на клиенте нет:
 * иначе чек разойдётся с номенклатурой, а склад модификаторов не спишется.
 */
class OptionPicker(context: Context) : LinearLayout(context) {

    /** Опция: товар Эвотор (`evotorUuid != null`) или схема из catalog API. */
    data class Opt(
        val key: String,
        val label: String,
        val price: BigDecimal,
        val evotorUuid: String?,
        val isDefault: Boolean = false,
        val required: Boolean = false,
        val zeroOption: Boolean = false,
        val available: Boolean = true,
    ) {
        /**
         * Фискально: реальный uuid; или zero-option / цена 0 (не в чек отдельной строкой).
         * available=false с backend — disabled.
         */
        val sellable: Boolean
            get() {
                if (!available) return false
                if (zeroOption || price.signum() == 0) return true
                return !evotorUuid.isNullOrBlank()
            }
    }

    data class Opts(
        val milk: List<Opt> = emptyList(),
        val syrup: List<Opt> = emptyList(),
        val sugar: List<Opt> = emptyList(),
        val extra: List<Opt> = emptyList(),
    )

    var onChange: (() -> Unit)? = null

    private var variants: List<CatalogProduct> = emptyList()
    private var opts: Opts = Opts()
    private var variant: CatalogProduct? = null
    private var milk: Opt? = null
    private var syrup: Opt? = null
    private var sugar: Opt? = null
    private val extras = LinkedHashMap<String, Opt>()

    private var defMilk: Opt? = null
    private var defSyrup: Opt? = null
    private var defSugar: Opt? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    /**
     * @param initial выбранный вариант (для комбо — вариант напитка по умолчанию)
     */
    fun bind(
        variants: List<CatalogProduct>,
        opts: Opts,
        initial: CatalogProduct? = null,
        preselect: Set<String> = emptySet(),
    ) {
        this.variants = variants.ifEmpty { listOfNotNull(initial) }
        this.opts = opts
        this.variant = initial?.let { want -> this.variants.firstOrNull { it.uuid == want.uuid } }
            ?: this.variants.firstOrNull { it.defaultVariant }
            ?: this.variants.firstOrNull()
        fun pickDefault(list: List<Opt>): Opt? =
            list.firstOrNull { it.isDefault && it.sellable }
                ?: list.firstOrNull { it.required && it.sellable }
                ?: list.firstOrNull { (it.zeroOption || it.price.signum() == 0) && it.sellable }
                ?: list.firstOrNull { it.sellable }
        defMilk = pickDefault(opts.milk)
        defSyrup = pickDefault(opts.syrup)
        defSugar = pickDefault(opts.sugar)
        // При редактировании восстанавливаем отмеченные ранее опции (в корзине они лежат отдельными строками).
        milk = opts.milk.firstOrNull { it.key in preselect } ?: defMilk
        syrup = opts.syrup.firstOrNull { it.key in preselect } ?: defSyrup
        sugar = opts.sugar.firstOrNull { it.key in preselect } ?: defSugar
        extras.clear()
        opts.extra.filter { it.key in preselect || (it.required && it.sellable) }.forEach { extras[it.key] = it }
        render()
    }

    val selectedVariant: CatalogProduct? get() = variant

    fun unitPrice(): BigDecimal {
        var sum = variant?.priceRub ?: BigDecimal.ZERO
        milk?.let { sum = sum.add(it.price) }
        syrup?.let { sum = sum.add(it.price) }
        sugar?.let { sum = sum.add(it.price) }
        extras.values.forEach { sum = sum.add(it.price) }
        return sum
    }

    /** Состав для строки чека: объём + не-дефолтные опции (как `modsLine` в index). */
    fun modsText(): String {
        val parts = ArrayList<String>(5)
        DrinkVariants.sizeLabel(variant?.sizeMl)?.let { parts.add(it) }
        milk?.takeIf { it.key != defMilk?.key }?.let { parts.add(it.label.lowercase()) }
        syrup?.takeIf { it.key != defSyrup?.key }?.let { parts.add(it.label.lowercase()) }
        sugar?.takeIf { it.key != defSugar?.key }?.let { parts.add("сахар " + it.label.lowercase()) }
        extras.values.forEach { parts.add(it.label.lowercase()) }
        return parts.joinToString(" · ")
    }

    /** Ключ конфигурации: одинаковые настройки → одна строка в чеке. */
    fun signature(): String {
        val base = variant?.uuid ?: return ""
        val sb = StringBuilder(base)
        milk?.takeIf { it.key != defMilk?.key }?.let { sb.append("|m:").append(it.key) }
        syrup?.takeIf { it.key != defSyrup?.key }?.let { sb.append("|s:").append(it.key) }
        sugar?.takeIf { it.key != defSugar?.key }?.let { sb.append("|g:").append(it.key) }
        extras.keys.sorted().forEach { sb.append("|e:").append(it) }
        return sb.toString()
    }

    /** Выбранные опции с товаром Эвотор — отдельные строки чека (zero-option без uuid не входят). */
    fun chosen(): List<Opt> =
        (listOfNotNull(milk, syrup, sugar) + extras.values)
            .filter { !it.evotorUuid.isNullOrBlank() && !it.zeroOption }

    private fun render() {
        removeAllViews()
        val variantValue = variant

        if (variants.size > 1) {
            header("Объём", "обязательно")
            grid(variants.map { v ->
                cell(
                    label = DrinkVariants.variantLabel(v),
                    price = null,
                    on = v.uuid == variantValue?.uuid,
                    enabled = true,
                    toggle = false,
                ) {
                    variant = v
                    render()
                    onChange?.invoke()
                }
            }, 3)
        }
        if (opts.milk.isNotEmpty()) {
            header("Молоко", "включено")
            grid(opts.milk.map { o ->
                cell(o.label, o.price, milk?.key == o.key, o.sellable, false) {
                    milk = if (milk?.key == o.key && !o.required) null else o
                    render(); onChange?.invoke()
                }
            }, 2)
        }
        if (opts.syrup.isNotEmpty()) {
            header("Сироп", "по желанию")
            grid(opts.syrup.map { o ->
                cell(o.label, o.price, syrup?.key == o.key, o.sellable, false) {
                    syrup = if (syrup?.key == o.key && !o.required) null else o
                    render(); onChange?.invoke()
                }
            }, 2)
        }
        if (opts.sugar.isNotEmpty()) {
            header("Сахар", "в порции")
            grid(opts.sugar.map { o ->
                cell(o.label, o.price.takeIf { it.signum() > 0 }, sugar?.key == o.key, o.sellable, false) {
                    sugar = if (sugar?.key == o.key && !o.required) null else o
                    render(); onChange?.invoke()
                }
            }, 3)
        }
        if (opts.extra.isNotEmpty()) {
            header("Добавки", if (opts.extra.any { it.required && it.sellable }) "обязательные отмечены" else "можно несколько")
            grid(opts.extra.map { o ->
                cell(o.label, o.price, extras.containsKey(o.key), o.sellable, true) {
                    if (extras.containsKey(o.key)) {
                        if (!o.required) extras.remove(o.key)
                    } else extras[o.key] = o
                    render(); onChange?.invoke()
                }
            }, 2)
        }
    }

    /** `.opt-label`: капс 9/650 ink-2 + подсказка справа; отступ группы 15. */
    private fun header(title: String, hint: String) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(15), 0, dp(7))
        }
        row.addView(TextView(context).apply {
            text = title.uppercase()
            textSize = Ds.sp(9f)
            letterSpacing = 0.11f
            weight(Fonts.STRONG)
            setTextColor(UiKit.INK_2)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = hint
            textSize = Ds.sp(8.5f)
            setTextColor(UiKit.MUTED)
        })
        addView(row)
    }

    private fun grid(cells: List<View>, columns: Int) {
        var i = 0
        var rowIndex = 0
        while (i < cells.size) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (c in 0 until columns) {
                if (i + c >= cells.size) {
                    row.addView(View(context), LayoutParams(0, 1, 1f))
                } else {
                    row.addView(cells[i + c], LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (c > 0) marginStart = dp(6)
                    })
                }
            }
            addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (rowIndex > 0) topMargin = dp(6)
            })
            rowIndex++
            i += columns
        }
    }

    /** Обводка выбранной опции: 1 dp ink + внутренняя 1 dp (`box-shadow: inset 0 0 0 1px ink`). */
    private fun optBackground(on: Boolean): android.graphics.drawable.Drawable {
        val base = UiKit.rounded(density, UiKit.SURFACE, 11, if (on) UiKit.INK else UiKit.LINE)
        if (!on) return base
        val inner = android.graphics.drawable.GradientDrawable().apply {
            setColor(0)
            cornerRadius = 10 * density
            setStroke(maxOf(1, density.toInt()), UiKit.INK)
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(base, inner)).apply {
            setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
        }
    }

    private fun tickIcon(color: Int, sizeDp: Int, iconDp: Int): View = android.widget.FrameLayout(context).apply {
        background = UiKit.rounded(density, color, sizeDp / 2, null)
        addView(icon(context, ru.sixthcup.evotor.R.drawable.ic_check, iconDp.toFloat(), UiKit.WHITE),
            android.widget.FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
    }

    private fun cell(
        label: String,
        price: BigDecimal?,
        on: Boolean,
        enabled: Boolean,
        toggle: Boolean,
        onClick: () -> Unit,
    ): View {
        val cell = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minimumHeight = dp(48)
            background = optBackground(on && enabled)
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.45f
            if (enabled) setOnClickListener { onClick() }
        }
        if (toggle) {
            // `.opt.toggle .box`: 17 dp, рамка 1.5, радиус 5; включён — чёрный с галочкой
            val box = android.widget.FrameLayout(context).apply {
                background = if (on) UiKit.rounded(density, UiKit.INK, 5, UiKit.INK)
                else UiKit.rounded(density, UiKit.SURFACE, 5, UiKit.LINE_STRONG)
                if (on) addView(icon(context, ru.sixthcup.evotor.R.drawable.ic_check, 11f, UiKit.WHITE),
                    android.widget.FrameLayout.LayoutParams(dp(11), dp(11), Gravity.CENTER))
            }
            cell.addView(box, LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(8) })
        }
        val textCol = LinearLayout(context).apply { orientation = VERTICAL }
        textCol.addView(TextView(context).apply {
            text = label
            textSize = 10.5f
            weight(Fonts.MEDIUM)
            setTextColor(if (on) UiKit.INK else UiKit.INK_2)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (price != null && !toggle) {
            textCol.addView(TextView(context).apply {
                text = if (price.signum() > 0) "+${UiKit.rub(price)}" else "0 ₽"
                textSize = Ds.sp(9f)
                setTextColor(if (on) UiKit.INK_2 else UiKit.MUTED)
            })
        }
        cell.addView(textCol, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (price != null && toggle) {
            cell.addView(TextView(context).apply {
                text = if (price.signum() > 0) "+${UiKit.rub(price)}" else "0 ₽"
                textSize = Ds.sp(9f)
                setTextColor(UiKit.MUTED)
                maxLines = 1
            })
        }
        if (!enabled) {
            cell.addView(TextView(context).apply {
                text = "нет в кассе"
                textSize = Ds.sp(7.5f)
                setTextColor(UiKit.MUTED)
            })
        } else if (!toggle && on) {
            cell.addView(tickIcon(UiKit.INK, 14, 9), LayoutParams(dp(14), dp(14)).apply { leftMargin = dp(6) })
        }
        return cell
    }
}
