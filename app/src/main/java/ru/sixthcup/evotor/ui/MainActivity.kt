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
import ru.sixthcup.evotor.data.Modifier
import ru.sixthcup.evotor.data.ModifierCatalog
import ru.sixthcup.evotor.data.ModifierGroup
import android.widget.CheckBox
import android.widget.ScrollView
import ru.sixthcup.evotor.domain.EvotorPaymentGateway
import ru.sixthcup.evotor.domain.LoyaltyReceiptFactory
import ru.sixthcup.evotor.domain.PaymentResult
import ru.sixthcup.evotor.scanner.ScannerReceiver
import ru.sixthcup.evotor.net.ApiClient
import ru.sixthcup.evotor.net.DeviceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Full cashier: menu → cart + loyalty → OpenSellReceiptCommand + Evotor payment (fiscal print).
 * Style: brand #002FA7, large touch targets, no emoji (TZ).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var menuScroll: android.widget.ScrollView
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


    /** Show syrups/toppings sheet for drinks, then add to cart. */
    private fun showRecipe(p: Product) {
        val sb = StringBuilder()
        sb.append(p.name).append(" · ").append(p.priceRub).append(" ₽\n\n")
        if (!p.recipeText.isNullOrBlank()) sb.append(p.recipeText).append("\n\n")
        else sb.append("Рецепт не задан в admin.\n\n")
        if (p.recipeCostRub != null) sb.append("Себес: ").append(p.recipeCostRub).append(" ₽\n")
        if (p.recipeSeconds != null) sb.append("Время: ~").append(p.recipeSeconds).append(" сек")
        android.app.AlertDialog.Builder(this)
            .setTitle("Рецепт")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun offerModifiersThenAdd(product: Product) {
        val mods = ModifierCatalog.forProduct(product)
        if (mods.isEmpty()) {
            cart.add(product)
            render()
            return
        }
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 16)
        }
        box.addView(TextView(this).apply {
            text = product.name + " — добавки? (сиропы, топпинги)"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })
        val checks = mutableListOf<Pair<CheckBox, Modifier>>()
        ModifierGroup.entries.forEach { group ->
            val groupMods = mods.filter { it.group == group }
            if (groupMods.isEmpty()) return@forEach
            box.addView(TextView(this).apply {
                text = group.title
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 12, 0, 6)
            })
            groupMods.forEach { m ->
                val cb = CheckBox(this).apply {
                    text = m.name + "  +" + (m.priceKopecks / 100) + " ₽"
                    textSize = 15f
                    minHeight = 48
                }
                box.addView(cb)
                checks.add(cb to m)
            }
        }
        scroll.addView(box)
        AlertDialog.Builder(this)
            .setTitle("Добавки")
            .setView(scroll)
            .setPositiveButton("В чек") { _, _ ->
                val selected = checks.filter { it.first.isChecked }.map { it.second }
                cart.add(product, selected)
                render()
            }
            .setNeutralButton("Без добавок") { _, _ ->
                cart.add(product)
                render()
            }
            .setNegativeButton("Отмена", null)
            .show()
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
        bottomPanel = findViewById(R.id.bottomPanel)
        menuScroll = findViewById(R.id.menuScroll)
        prefs = Prefs(this)
        if (prefs.isEnrolled) {
            step = Step.SALE
            loadCatalogCache()
            refreshCatalogAsync(showToast = false)
        } else {
            step = Step.ENROLL
        }
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
        bottomPanel.removeAllViews()
        when (step) {
            Step.ENROLL -> {
                bottomPanel.visibility = android.view.View.GONE
                renderEnroll()
            }
            Step.SALE -> {
                bottomPanel.visibility = android.view.View.VISIBLE
                renderSale()
            }
            Step.RESULT -> {
                bottomPanel.visibility = android.view.View.GONE
                renderResult()
            }
        }
    }

    // ---------- ENROLL ----------
    private fun renderEnroll() {
        header("Регистрация кассы")
        content.addView(label("Код из admin → Кассы. Меню с backend."))
        val base = EditText(this).apply {
            hint = "URL backend"
            setText(prefs.apiBaseUrl)
            setPadding(24, 24, 24, 24)
        }
        content.addView(base)
        val code = EditText(this).apply {
            hint = "Код регистрации кассы"
            setPadding(24, 24, 24, 24)
        }
        content.addView(code)
        content.addView(primaryBtn("Зарегистрировать") {
            val c = code.text.toString().trim()
            val url = base.text.toString().trim().ifBlank { prefs.apiBaseUrl }
            if (c.isEmpty()) {
                toast("Введите код"); return@primaryBtn
            }
            prefs.apiBaseUrl = url
            enrollAsync(c)
        })
    }

    private fun enrollAsync(code: String) {
        toast("Регистрация…")
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val pk = prefs.publicKey.ifBlank {
                    DeviceKeys.generatePublicKeyPlaceholder().also { prefs.publicKey = it }
                }
                val result = withContext(Dispatchers.IO) {
                    ApiClient(prefs.apiBaseUrl).enroll(code, pk)
                }
                prefs.deviceId = result.deviceId
                prefs.deviceToken = result.deviceToken
                prefs.storeName = result.storeName
                prefs.enrollCode = code
                prefs.deviceName = "Эвотор · ${result.storeName}"
                val dir = withContext(Dispatchers.IO) {
                    ApiClient(prefs.apiBaseUrl).fetchDirectory()
                }
                applyDirectory(dir)
                step = Step.SALE
                render()
                toast("Касса #${result.deviceId} · ${dir.products.size} товаров")
            } catch (e: Exception) {
                toast(e.message ?: "Ошибка регистрации")
            }
        }
    }

    private fun loadCatalogCache() {
        val json = prefs.catalogJson
        if (json.isBlank()) return
        try {
            applyDirectory(ApiClient.parseDirectory(json))
        } catch (_: Exception) {
        }
    }

    private fun applyDirectory(dir: ApiClient.Directory) {
        Catalog.replaceAll(dir.products)
        prefs.catalogJson = dir.rawJson
    }

    private fun refreshCatalogAsync(showToast: Boolean) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val dir = withContext(Dispatchers.IO) {
                    ApiClient(prefs.apiBaseUrl).fetchDirectory()
                }
                applyDirectory(dir)
                if (step == Step.SALE) render()
                if (showToast) toast("Меню: ${dir.products.size} позиций")
                flushPendingReceipts()
            } catch (e: Exception) {
                if (showToast) toast(e.message ?: "Не удалось обновить меню")
            }
        }
    }

    private fun flushPendingReceipts() {
        val token = prefs.deviceToken
        if (token.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val arr = JSONArray(prefs.pendingReceipts)
                if (arr.length() == 0) return@launch
                val list = (0 until arr.length()).map { arr.getString(it) }
                if (ApiClient(prefs.apiBaseUrl).syncReceipts(token, list)) {
                    prefs.pendingReceipts = "[]"
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun queueReceipt(payload: String) {
        val arr = try {
            JSONArray(prefs.pendingReceipts)
        } catch (_: Exception) {
            JSONArray()
        }
        arr.put(payload)
        prefs.pendingReceipts = arr.toString()
        flushPendingReceipts()
    }

    // ---------- SALE (menu + cart + client) ----------
    private fun renderSale() {
        headerBarCompact()
        content.addView(clientStripCompact())
        content.addView(categoryTabs())
        content.addView(productGrid())

        bottomPanel.addView(sectionTitle("Чек"))
        if (cart.isEmpty()) {
            bottomPanel.addView(hint("Пусто — выберите напиток сверху"))
        } else {
            val maxLines = 4
            val lines = cart.snapshot()
            lines.take(maxLines).forEach { line ->
                bottomPanel.addView(cartLineRowCompact(line.product, line.qty, line.isFree))
            }
            if (lines.size > maxLines) {
                bottomPanel.addView(hint("+ ещё " + (lines.size - maxLines)))
            }
        }
        bottomPanel.addView(totalsBlockCompact())
        bottomPanel.addView(loyaltyStrip())
        bottomPanel.addView(primaryBtn("Оплатить") { pay() })
    }

    private fun productGrid(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 4, 4, 8)
        }
        val products = Catalog.products.filter { it.category == category }
        if (Catalog.isEmpty()) {
            wrap.addView(label("Меню пусто. ⋯ → обновить с сервера"))
            return wrap
        }
        if (products.isEmpty()) {
            wrap.addView(hint("Нет позиций в «" + category.title + "»"))
            return wrap
        }
        var i = 0
        while (i < products.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            row.addView(productTile(products[i]).also {
                it.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).also { lp ->
                    lp.setMargins(4, 4, 4, 4)
                }
            })
            if (i + 1 < products.size) {
                row.addView(productTile(products[i + 1]).also {
                    it.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).also { lp ->
                        lp.setMargins(4, 4, 4, 4)
                    }
                })
            } else {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                })
            }
            wrap.addView(row)
            i += 2
        }
        return wrap
    }

    private fun productTile(p: Product): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.white))
            setPadding(dp(10), dp(12), dp(10), dp(12))
            minimumHeight = dp(72)
            setOnClickListener { offerModifiersThenAdd(p) }
            setOnLongClickListener {
                showRecipe(p)
                true
            }
        }
        box.addView(TextView(this).apply {
            text = p.name
            setTextColor(color(R.color.ink))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
        })
        box.addView(TextView(this).apply {
            text = "" + p.priceRub + " ₽"
            setTextColor(color(R.color.brand))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        })
        return box
    }

    private fun headerBarCompact() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(color(R.color.brand))
            setPadding(dp(12), dp(28), dp(8), dp(12))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = prefs.storeName.ifBlank { prefs.deviceName }.ifBlank { "6.7 Coffee" }
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        bar.addView(smallBtn("⋯") { showCashierMenu() }.also {
            it.setTextColor(0xFFFFFFFF.toInt())
            it.setBackgroundColor(0x33FFFFFF)
        })
        content.addView(bar)
    }

    private fun showCashierMenu() {
        val opts = arrayOf("Обновить меню с сервера", "Сбросить привязку кассы (dev)")
        android.app.AlertDialog.Builder(this)
            .setTitle("Касса")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> refreshCatalogAsync(showToast = true)
                    1 -> {
                        prefs.deviceToken = ""
                        prefs.enrollCode = ""
                        step = Step.ENROLL
                        render()
                    }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun clientStripCompact(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(color(R.color.brand_soft))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val c = card
        val info = TextView(this).apply {
            text = if (c != null) {
                "Гость · " + c.progress + "/" + c.cupsForFree + " · кэшбэк " + c.cashbackRub + " ₽"
            } else {
                "Гость не выбран"
            }
            setTextColor(color(R.color.ink))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        box.addView(info)
        box.addView(smallBtn(if (c != null) "Сброс" else "QR") {
            if (c != null) {
                card = null
                applyFree = false
                cashbackUseRub = 0
                cart.markFree(null)
                render()
            } else promptCard()
        })
        return box
    }

    private fun cartLineRowCompact(p: Product, qty: Int, free: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(4, 2, 4, 2)
        }
        row.addView(TextView(this).apply {
            text = if (free) p.name + " (6-й)" else p.name
            setTextColor(color(R.color.ink))
            textSize = 13f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        row.addView(TextView(this).apply {
            text = "×" + qty
            setTextColor(color(R.color.ink_secondary))
            textSize = 13f
            setPadding(8, 0, 8, 0)
        })
        row.addView(smallBtn("−") {
            cart.setQty(p.id, qty - 1)
            if (free && qty - 1 <= 0) {
                applyFree = false
                cart.markFree(null)
            }
            render()
        })
        return row
    }

    private fun totalsBlockCompact(): View {
        val c = card
        val wantFree = applyFree && (c?.freeAvailable ?: 0) > 0 && cart.freeLine() != null
        val totals = ru.sixthcup.evotor.data.LoyaltyRules.totals(cart, c, wantFree, cashbackUseRub)
        return TextView(this).apply {
            text = "К оплате  " + (totals.toPayKopecks / 100) + " ₽"
            setTextColor(color(R.color.brand))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(8, 8, 8, 4)
        }
    }

    private fun loyaltyStrip(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        val c = card
        if (c != null && c.freeAvailable > 0) {
            col.addView(smallBtn(if (applyFree) "6-й: ВКЛ" else "6-й стакан") {
                toggleFree()
            }.also { it.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
        }
        if (c != null && c.cashbackRub > 0) {
            col.addView(smallBtn(
                if (cashbackUseRub > 0) "−" + cashbackUseRub + " ₽" else "Кэшбэк"
            ) { askCashback() }.also { it.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
        }
        col.addView(smallBtn("Очистить") {
            cart.clear()
            applyFree = false
            cashbackUseRub = 0
            cart.markFree(null)
            render()
        }.also { it.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
        return col
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
                setTextColor(color(R.color.ink))
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
            setTextColor(color(R.color.ink))
            textSize = 13f
        })
        row.addView(col)
        row.addView(Button(this).apply {
            text = "+"
            textSize = 20f
            setOnClickListener {
                offerModifiersThenAdd(p)
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
                        val loyalty = LoyaltyReceiptFactory.create(
                            c, cart, wantFree, cashbackUseRub, totals.toPayKopecks / 100, "evotor"
                        )
                        queueReceipt(loyalty)
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
        setTextColor(color(R.color.ink))
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
    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.ink))
        textSize = 13f
        setPadding(24, 8, 24, 8)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
