package ru.sixthcup.evotor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.sixthcup.evotor.R
import ru.sixthcup.evotor.data.CardParser
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.Catalog
import ru.sixthcup.evotor.data.Category
import ru.sixthcup.evotor.data.ClientCard
import ru.sixthcup.evotor.data.LoyaltyRules
import ru.sixthcup.evotor.data.Prefs
import ru.sixthcup.evotor.data.Product
import ru.sixthcup.evotor.domain.EvotorPaymentGateway
import ru.sixthcup.evotor.domain.PaymentResult
import ru.sixthcup.evotor.scanner.ScannerReceiver

/**
 * Full cashier: menu → cart + loyalty → OpenSellReceiptCommand + Evotor payment (fiscal print).
 * Style: brand #002FA7, large touch targets, no emoji (TZ).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var prefs: Prefs
    private val cart = Cart()
    private val payment = EvotorPaymentGateway()

    private enum class Step { ENROLL, SALE, RESULT }
    private var step = Step.SALE

    private var category: Category = Category.DRINKS
    private var card: ClientCard? = null
    private var applyFree = false
    private var cashbackUseRub = 0
    private var lastFiscalId = ""
    private var resultMessage = ""

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = intent.getStringExtra(ScannerReceiver.EXTRA_CODE) ?: return
            attachCard(code)
        }
    }

    private fun attachCard(code: String) {
        if (code.isBlank()) {
            toast("Пустой код")
            return
        }
        card = CardParser.parse(code)
        applyFree = false
        cashbackUseRub = 0
        cart.markFree(null)
        render()
    }

    private fun promptCard() {
        val input = EditText(this).apply {
            hint = "Код карты / QR (DEMO / FREE / CB)"
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("Карта / QR")
            .setMessage("Отсканируйте QR или введите код вручную")
            .setView(input)
            .setPositiveButton("Применить") { _, _ ->
                attachCard(input.text.toString().trim())
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        prefs = Prefs(this)
        step = if (prefs.enrollCode.isBlank()) Step.ENROLL else Step.SALE
        render()
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(ScannerReceiver.ACTION_INTERNAL_SCAN)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(scanReceiver, f, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(scanReceiver, f)
        }
    }

    override fun onPause() {
        try {
            unregisterReceiver(scanReceiver)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    private fun render() {
        content.removeAllViews()
        when (step) {
            Step.ENROLL -> renderEnroll()
            Step.SALE -> renderSale()
            Step.RESULT -> renderResult()
        }
    }

    // ---------- ENROLL ----------
    private fun renderEnroll() {
        header("Регистрация кассы")
        val code = EditText(this).apply {
            hint = "Код (DEMO1234)"
            setText("DEMO1234")
            setPadding(24, 24, 24, 24)
        }
        content.addView(code)
        content.addView(primaryBtn("Зарегистрировать") {
            val c = code.text.toString().trim()
            if (c.isEmpty()) {
                toast("Введите код"); return@primaryBtn
            }
            prefs.enrollCode = c
            prefs.deviceName = "Эвотор · $c"
            step = Step.SALE
            render()
        })
    }

    // ---------- SALE (menu + cart + client) ----------
    private fun renderSale() {
        headerBar()

        // Client strip
        content.addView(clientStrip())

        // Categories
        content.addView(categoryTabs())

        // Product grid
        content.addView(sectionTitle(category.title))
        Catalog.products.filter { it.category == category }.forEach { p ->
            content.addView(productRow(p))
        }

        // Cart
        content.addView(sectionTitle("Чек"))
        if (cart.isEmpty()) {
            content.addView(hint("Добавьте позиции из меню"))
        } else {
            cart.snapshot().forEach { line ->
                content.addView(cartLineRow(line.product, line.qty, line.isFree))
            }
        }

        content.addView(totalsBlock())
        content.addView(actionButtons())
    }

    private fun headerBar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.brand))
            setPadding(28, 40, 28, 20)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = 8 }
        }
        bar.addView(TextView(this).apply {
            text = "Шестой стакан · ${prefs.deviceName}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        })
        bar.addView(TextView(this).apply {
            text = "Продажа из приложения"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
        })
        content.addView(bar)
    }

    private fun clientStrip(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.brand_soft))
            setPadding(20, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also {
                it.setMargins(12, 8, 12, 8)
            }
        }
        val c = card
        if (c == null) {
            box.addView(TextView(this).apply {
                text = "Клиент не привязан"
                setTextColor(color(R.color.ink))
                textSize = 15f
            })
            box.addView(TextView(this).apply {
                text = "Сканер QR или код: DEMO / FREE / CB"
                setTextColor(color(R.color.ink_secondary))
                textSize = 12f
            })
        } else {
            box.addView(TextView(this).apply {
                text = "Клиент №${c.userId} · ${c.progress}/${c.cupsForFree} · free ${c.freeAvailable} · кэшбэк ${c.cashbackRub} ₽"
                setTextColor(color(R.color.ink))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })
            val bits = buildList {
                if (c.couponPercent != null) add("купон −${c.couponPercent}%")
                if (c.couponFixedRub != null) add("купон −${c.couponFixedRub} ₽")
                if (c.freeAvailable > 0) add("доступен 6-й стакан")
            }.joinToString(" · ")
            if (bits.isNotEmpty()) {
                box.addView(TextView(this).apply {
                    text = bits
                    setTextColor(color(R.color.brand))
                    textSize = 13f
                })
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        row.addView(smallBtn("Карта / QR") { promptCard() }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).also { lp -> lp.marginEnd = 8 }
        })
        row.addView(smallBtn("Без карты") {
            card = null
            applyFree = false
            cashbackUseRub = 0
            cart.markFree(null)
            render()
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        box.addView(row)
        return box
    }

    private fun categoryTabs(): View {
        val scroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
        }
        Category.entries.forEach { cat ->
            row.addView(Button(this).apply {
                text = cat.title
                isAllCaps = false
                if (cat == category) {
                    setBackgroundColor(color(R.color.brand))
                    setTextColor(0xFFFFFFFF.toInt())
                }
                setOnClickListener {
                    category = cat
                    render()
                }
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginEnd = 8 }
            })
        }
        scroll.addView(row)
        return scroll
    }

    private fun productRow(p: Product): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also {
                it.setMargins(12, 4, 12, 4)
            }
        }
        // photo placeholder
        row.addView(View(this).apply {
            setBackgroundColor(color(R.color.brand_mid))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).also { it.marginEnd = 12 }
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        col.addView(TextView(this).apply {
            text = p.name
            setTextColor(color(R.color.ink))
            textSize = 16f
        })
        col.addView(TextView(this).apply {
            text = "${p.priceRub} ₽" + if (p.isFreeEligible) " · можно 6-й" else ""
            setTextColor(color(R.color.ink_secondary))
            textSize = 13f
        })
        row.addView(col)
        row.addView(Button(this).apply {
            text = "+"
            textSize = 20f
            setOnClickListener {
                cart.add(p)
                render()
            }
        })
        return row
    }

    private fun cartLineRow(p: Product, qty: Int, free: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also {
                it.setMargins(12, 2, 12, 2)
            }
        }
        row.addView(TextView(this).apply {
            text = buildString {
                append(p.name)
                if (free) append(" · БЕСПЛАТНО")
            }
            setTextColor(if (free) color(R.color.ok) else color(R.color.ink))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        row.addView(Button(this).apply {
            text = "−"
            setOnClickListener {
                cart.setQty(p.id, qty - 1)
                if (free && qty - 1 <= 0) {
                    applyFree = false
                    cart.markFree(null)
                }
                render()
            }
        })
        row.addView(TextView(this).apply {
            text = qty.toString()
            setPadding(16, 0, 16, 0)
            textSize = 16f
        })
        row.addView(Button(this).apply {
            text = "+"
            setOnClickListener {
                cart.setQty(p.id, qty + 1)
                render()
            }
        })
        return row
    }

    private fun totalsBlock(): View {
        val c = card
        val wantFree = applyFree && (c?.freeAvailable ?: 0) > 0 && cart.freeLine() != null
        val totals = LoyaltyRules.totals(cart, c, wantFree, cashbackUseRub)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also {
                it.setMargins(12, 12, 12, 8)
            }
        }
        fun line(label: String, value: String, bold: Boolean = false) {
            box.addView(TextView(this).apply {
                text = "$label: $value"
                setTextColor(color(R.color.ink))
                textSize = if (bold) 18f else 14f
                if (bold) setTypeface(null, Typeface.BOLD)
                setPadding(0, 4, 0, 4)
            })
        }
        line("Скидка по купону", "−${totals.discountKopecks / 100} ₽")
        if (wantFree) line("6-й стакан", "−${totals.freeValueKopecks / 100} ₽")
        if (cashbackUseRub > 0) line("Кэшбэк", "−${totals.cashbackUseRub} ₽")
        line("К оплате", "${totals.toPayKopecks / 100} ₽", bold = true)
        return box
    }

    private fun actionButtons(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 24)
        }
        val c = card
        if (c != null && c.freeAvailable > 0) {
            col.addView(primaryBtn(if (applyFree) "Бесплатный стакан: ВКЛ" else "Выдать 6-й стакан") {
                toggleFree()
            })
        }
        if (c != null && c.cashbackRub > 0) {
            col.addView(secondaryBtn(
                if (cashbackUseRub > 0) "Кэшбэк: −$cashbackUseRub ₽ (изменить)"
                else "Списать кэшбэк (${c.cashbackRub} ₽)?"
            ) { askCashback() })
        }
        col.addView(primaryBtn("Оплатить") { pay() })
        col.addView(secondaryBtn("Очистить чек") {
            cart.clear()
            applyFree = false
            cashbackUseRub = 0
            cart.markFree(null)
            render()
        })
        return col
    }

    private fun toggleFree() {
        val c = card ?: return
        if (c.freeAvailable <= 0) {
            toast("Нет бесплатного стакана"); return
        }
        if (applyFree) {
            applyFree = false
            cart.markFree(null)
            render()
            return
        }
        val eligible = cart.snapshot().filter { it.product.isFreeEligible }
        if (eligible.isEmpty()) {
            toast("Добавьте напиток — бесплатным может быть только напиток")
            return
        }
        // default: most expensive eligible
        val best = eligible.maxByOrNull { it.product.priceKopecks }!!
        cart.markFree(best.product.id)
        applyFree = true
        render()
        toast("${best.product.name} — бесплатно (6-й)")
    }

    private fun askCashback() {
        val c = card ?: return
        val wantFree = applyFree && cart.freeLine() != null
        val totals = LoyaltyRules.totals(cart, c, wantFree, 0)
        val maxCb = minOf(c.cashbackRub, totals.toPayKopecks / 100)
        if (maxCb <= 0) {
            toast("Нечего списывать"); return
        }
        AlertDialog.Builder(this)
            .setTitle("Кэшбэк")
            .setMessage("Списать до $maxCb ₽ с баланса ${c.cashbackRub} ₽?")
            .setPositiveButton("Да, списать") { _, _ ->
                cashbackUseRub = maxCb
                render()
            }
            .setNegativeButton("Нет") { _, _ ->
                cashbackUseRub = 0
                render()
            }
            .setNeutralButton("Часть…") { _, _ ->
                val input = EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    hint = "Сумма 1…$maxCb"
                    setPadding(40, 30, 40, 30)
                }
                AlertDialog.Builder(this)
                    .setTitle("Сумма кэшбэка")
                    .setView(input)
                    .setPositiveButton("OK") { _, _ ->
                        cashbackUseRub = input.text.toString().toIntOrNull()?.coerceIn(0, maxCb) ?: 0
                        render()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            .show()
    }


    private fun pay() {
        if (cart.isEmpty()) {
            toast("Корзина пуста"); return
        }
        val c = card
        val wantFree = applyFree && (c?.freeAvailable ?: 0) > 0 && cart.freeLine() != null
        val totals = LoyaltyRules.totals(cart, c, wantFree, cashbackUseRub)
        toast("Открываем чек в Эвоторе…")
        payment.charge(this, cart, totals, c, wantFree) { result ->
            runOnUiThread {
                when (result) {
                    is PaymentResult.OpenedForPayment -> {
                        // Clear local cart — fiscal continues on Evotor payment screen
                        cart.clear()
                        applyFree = false
                        cashbackUseRub = 0
                        lastFiscalId = "evotor"
                        step = Step.RESULT
                        resultMessage = result.message
                        render()
                    }
                    is PaymentResult.Err -> toast(result.message)
                }
            }
        }
    }


    private fun renderResult() {
        header("Оплата в Эвоторе")
        content.addView(hint(resultMessage.ifBlank {
            "Чек передан в кассу Эвотора. Оплатите на экране терминала — фискальный чек напечатается сам."
        }))
        content.addView(hint(
            "Клиенту QR не нужен: покупка и кэшбэк появятся в приложении после выгрузки из Эвотора на наш сервер."
        ))
        content.addView(primaryBtn("Новая продажа") {
            step = Step.SALE
            card = null
            resultMessage = ""
            render()
        })
        content.addView(secondaryBtn("Ещё заказ этому клиенту") {
            step = Step.SALE
            resultMessage = ""
            render()
        })
    }

    // ---------- UI helpers ----------
    private fun header(title: String) {
        content.addView(TextView(this).apply {
            text = title
            setBackgroundColor(color(R.color.brand))
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setPadding(28, 48, 28, 28)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = 12 }
        })
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        setTextColor(color(R.color.ink))
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        setPadding(20, 16, 20, 8)
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        setTextColor(color(R.color.ink_secondary))
        setPadding(20, 8, 20, 8)
        textSize = 14f
    }

    private fun primaryBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setBackgroundColor(color(R.color.brand))
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(24, 28, 24, 28)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = 8 }
        setOnClickListener { onClick() }
    }

    private fun secondaryBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setPadding(24, 24, 24, 24)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = 6 }
        setOnClickListener { onClick() }
    }

    private fun smallBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 13f
        setOnClickListener { onClick() }
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
