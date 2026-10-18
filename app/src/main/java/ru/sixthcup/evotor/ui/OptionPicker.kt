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
    ) {
        /** Провести фискально можно только реальный товар; бесплатную схему — как подпись. */
        val sellable: Boolean get() = !evotorUuid.isNullOrBlank() || price.signum() == 0
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
        defMilk = opts.milk.firstOrNull { it.isDefault } ?: opts.milk.firstOrNull { it.price.signum() == 0 }
        defSyrup = opts.syrup.firstOrNull { it.isDefault } ?: opts.syrup.firstOrNull { it.price.signum() == 0 }
        defSugar = opts.sugar.firstOrNull { it.isDefault } ?: opts.sugar.firstOrNull { it.price.signum() == 0 }
        // При редактировании восстанавливаем отмеченные ранее опции (в корзине они лежат отдельными строками).
        milk = opts.milk.firstOrNull { it.key in preselect } ?: defMilk
        syrup = opts.syrup.firstOrNull { it.key in preselect } ?: defSyrup
        sugar = opts.sugar.firstOrNull { it.key in preselect } ?: defSugar
        extras.clear()
        opts.extra.filter { it.key in preselect }.forEach { extras[it.key] = it }
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

    /** Все выбранные опции с товаром Эвотор — их нужно добавить отдельными строками чека. */
    fun chosen(): List<Opt> = listOfNotNull(milk, syrup, sugar) + extras.values

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
                    milk = if (milk?.key == o.key) null else o
                    render(); onChange?.invoke()
                }
            }, 2)
        }
        if (opts.syrup.isNotEmpty()) {
            header("Сироп", "по желанию")
            grid(opts.syrup.map { o ->
                cell(o.label, o.price, syrup?.key == o.key, o.sellable, false) {
                    syrup = if (syrup?.key == o.key) null else o
                    render(); onChange?.invoke()
                }
            }, 2)
        }
        if (opts.sugar.isNotEmpty()) {
            header("Сахар", "в порции")
            grid(opts.sugar.map { o ->
                cell(o.label, o.price.takeIf { it.signum() > 0 }, sugar?.key == o.key, o.sellable, false) {
                    sugar = if (sugar?.key == o.key) null else o
                    render(); onChange?.invoke()
                }
            }, 3)
        }
        if (opts.extra.isNotEmpty()) {
            header("Добавки", "можно несколько")
            grid(opts.extra.map { o ->
                cell(o.label, o.price, extras.containsKey(o.key), o.sellable, true) {
                    if (extras.containsKey(o.key)) extras.remove(o.key) else extras[o.key] = o
                    render(); onChange?.invoke()
                }
            }, 2)
        }
    }

    private fun header(title: String, hint: String) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(16), 0, dp(7))
        }
        row.addView(TextView(context).apply {
            text = title
            textSize = 9f
            setTypeface(null, Typeface.BOLD)
            setTextColor(UiKit.INK_2)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = hint
            textSize = 8.5f
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
            setPadding(dp(10), dp(8), dp(8), dp(8))
            minimumHeight = dp(48)
            background = UiKit.rounded(density, UiKit.SURFACE, 11, if (on && enabled) UiKit.INK else UiKit.LINE)
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.45f
            if (enabled) setOnClickListener { onClick() }
        }
        if (toggle) {
            cell.addView(TextView(context).apply {
                text = if (on) "✓" else ""
                textSize = 9f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (on) UiKit.WHITE else UiKit.MUTED)
                background = UiKit.rounded(
                    density,
                    if (on) UiKit.BRAND else UiKit.SURFACE,
                    5,
                    if (on) UiKit.BRAND else UiKit.LINE_STRONG,
                )
            }, LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(7) })
        }
        val textCol = LinearLayout(context).apply { orientation = VERTICAL }
        textCol.addView(TextView(context).apply {
            text = label
            textSize = 10.5f
            setTextColor(if (on) UiKit.INK else UiKit.INK_2)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (price != null) {
            textCol.addView(TextView(context).apply {
                text = if (price.signum() > 0) "+${UiKit.rub(price)}" else "0 ₽"
                textSize = 9.5f
                setTextColor(UiKit.MUTED)
            })
        }
        cell.addView(textCol, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!enabled) {
            cell.addView(TextView(context).apply {
                text = "нет в кассе"
                textSize = 7.5f
                setTextColor(UiKit.MUTED)
            })
        } else if (!toggle) {
            cell.addView(TextView(context).apply {
                text = if (on) "✓" else ""
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (on) UiKit.INK else UiKit.MUTED)
            }, LayoutParams(dp(16), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return cell
    }
}
