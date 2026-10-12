package ru.sixthcup.evotor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import ru.sixthcup.evotor.R
import ru.sixthcup.evotor.data.CardInfo
import ru.sixthcup.evotor.data.CardSession
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CartLine
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.DiscountCalc
import ru.sixthcup.evotor.data.InventoryRepository
import ru.sixthcup.evotor.data.PendingPayment
import ru.sixthcup.evotor.net.LoyaltyApi
import ru.sixthcup.evotor.net.CatalogApi
import ru.sixthcup.evotor.data.Topping
import ru.sixthcup.evotor.data.UpsellItem
import ru.sixthcup.evotor.scanner.ScannerReceiver
import ru.sixthcup.evotor.sell.SellLauncher
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.concurrent.thread

/**
 * Кассовый UI 6.7 Coffee (редизайн 4.2.0, см. CHANGELOG-CASHIER-UX.md).
 * Экран = верхняя полоса (карта) + прокручиваемый контент + нижняя полоса (итог и «К оплате»).
 * Никаких новых зависимостей: только платформа, AppCompat, Material (уже подключены).
 */
class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout

    private var products: List<CatalogProduct> = emptyList()
    private var upsellDefault: List<UpsellItem> = emptyList()
    private var productsLoading = false
    private var productsError = false
    private var catalogGen = 0
    private var query = ""
    private var group: String? = null

    private var screen: Screen = Screen.CARD
    private var resolving = false
    private var input: EditText? = null
    private var notice: String? = null
    private var noticeKind: Kind = Kind.ERROR
    private var lastCardCode: String? = null   // только в памяти процесса, на диск не пишется
    private var lastCardLabel: String? = null

    private var catalogHost: LinearLayout? = null
    private var searchView: EditText? = null
    private var payButton: Button? = null
    private var itemsView: TextView? = null
    private val badges = HashMap<String, TextView>()
    private var cartDialog: BottomSheetDialog? = null
    private var checkoutDialog: BottomSheetDialog? = null

    private val handler = Handler(Looper.getMainLooper())
    private var qrRunnable: Runnable? = null

    private enum class Screen { CARD, ORDER, PAYMENT }
    private enum class Kind { ERROR, WARN, INFO, OK }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = intent.getStringExtra(ScannerReceiver.EXTRA_CODE).orEmpty().trim()
            if (code.isEmpty() || resolving || screen == Screen.PAYMENT) return
            val active = CardSession.get(this@MainActivity)?.takeIf { it.code.isNotEmpty() }
            if (screen == Screen.CARD || active == null) {
                applyScan(code)
            } else {
                // Карта уже привязана: не заменяем молча и не показываем исчезающий Toast.
                AlertDialog.Builder(this@MainActivity).setTitle("Заменить карту?")
                    .setMessage("Сейчас выбрана карта ${active.cardCode.ifBlank { "по QR" }}. Отсканирована другая.")
                    .setPositiveButton("Заменить") { _, _ -> CardSession.clear(this@MainActivity); applyScan(code) }
                    .setNegativeButton("Оставить текущую", null).show()
            }
        }
    }

    private fun applyScan(code: String) {
        screen = Screen.CARD
        safeRender()
        input?.setText(code)
        resolve(code)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            Cart.attach(this)
            val frame = findViewById<FrameLayout>(R.id.root)
            val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            topBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)) }
            val scroll = ScrollView(this)
            root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
            scroll.addView(root)
            bottomBar = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(PAGE); setPadding(dp(12), dp(8), dp(12), dp(8)); elevation = dp(8).toFloat()
            }
            shell.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
            shell.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            shell.addView(bottomBar, LinearLayout.LayoutParams(MATCH, WRAP))
            frame.addView(shell, FrameLayout.LayoutParams(MATCH, MATCH))

            screen = when {
                PendingPayment.active(this) -> Screen.PAYMENT
                CardSession.get(this)?.code?.isNotEmpty() == true || !Cart.isEmpty() -> Screen.ORDER
                else -> Screen.CARD
            }
            loadProducts()
            safeRender()
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate", e)
            Toast.makeText(this, "Не удалось запустить приложение. Перезапустите его.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ───────────────────────── каталог (в фоне) ─────────────────────────

    private fun loadProducts() {
        val gen = ++catalogGen
        productsLoading = true; productsError = false
        if (screen == Screen.ORDER) fillCatalog()
        val ctx = applicationContext
        thread(name = "catalog-load") {
            val inv = try { InventoryRepository.load(ctx) } catch (e: Throwable) { Log.e(TAG, "catalog", e); null }
            val enrich = try { CatalogApi.fetch(null) } catch (e: Throwable) { Log.e(TAG, "enrich", e); CatalogApi.Payload(emptyMap(), emptyList()) }
            val merged = if (inv != null) CatalogApi.merge(inv, enrich) else null
            runOnUiThread {
                if (isFinishing || isDestroyed || gen != catalogGen) return@runOnUiThread
                productsLoading = false
                if (merged == null) productsError = true else {
                    products = merged
                    upsellDefault = enrich.upsellDefault
                }
                if (screen == Screen.ORDER) fillCatalog()
            }
        }
    }

    // ───────────────────────── рендер ─────────────────────────

    private fun safeRender() {
        try { render() } catch (e: Throwable) {
            Log.e(TAG, "render", e)
            Toast.makeText(this, "Не удалось обновить экран", Toast.LENGTH_SHORT).show()
        }
    }

    private fun render() {
        dismissSheets()
        root.removeAllViews(); topBar.removeAllViews(); bottomBar.removeAllViews()
        catalogHost = null; searchView = null; payButton = null; itemsView = null; badges.clear()
        when (screen) { Screen.CARD -> renderCard(); Screen.ORDER -> renderOrder(); Screen.PAYMENT -> renderPayment() }
        topBar.visibility = if (topBar.childCount == 0) View.GONE else View.VISIBLE
        bottomBar.visibility = if (bottomBar.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun dismissSheets() {
        try { cartDialog?.dismiss() } catch (_: Throwable) {}
        try { checkoutDialog?.dismiss() } catch (_: Throwable) {}
        cartDialog = null; checkoutDialog = null
    }

    // ── Экран 1: карта ──

    private fun renderCard() {
        val card = CardSession.get(this)?.takeIf { it.code.isNotEmpty() }
        topBar.addView(text("6.7 Coffee", 22f, BRAND, true))
        root.addView(text("Карта гостя", 18f, INK, true).apply { setPadding(0, dp(4), 0, dp(8)) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val field = EditText(this).apply {
            hint = "Номер карты или QR гостя"
            inputType = InputType.TYPE_CLASS_TEXT          // QR-токен содержит буквы и точки
            setRawInputType(InputType.TYPE_CLASS_NUMBER)   // но на экране сразу цифровая клавиатура
            isSingleLine = true
            textSize = 20f
            setTextColor(INK)
            setHintTextColor(HINT)
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            isEnabled = !resolving
            background = rounded(WHITE, 12, LINE)
            setPadding(dp(12), 0, dp(12), 0)
        }
        input = field
        row.addView(field, LinearLayout.LayoutParams(0, dp(56), 1f))
        row.addView(button("OK", true, 56) { resolve(field.text.toString()) }, LinearLayout.LayoutParams(dp(88), dp(56)).apply { leftMargin = dp(8) })
        root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        if (resolving) root.addView(banner("Проверяем карту…", Kind.INFO))
        notice?.let { root.addView(banner(it, noticeKind)) }
        if (card != null) {
            root.addView(text("Выбрана карта", 14f, HINT, false))
            root.addView(cardChip(card, clickable = false), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        } else {
            val last = lastCardCode
            if (last != null && !resolving) {
                root.addView(button("Последняя карта: ${lastCardLabel ?: "повторить"}", false, 56) { resolve(last) },
                    LinearLayout.LayoutParams(MATCH, dp(56)).apply { bottomMargin = dp(8) })
            }
        }

        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) { resolve(field.text.toString()); true } else false
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString()?.trim().orEmpty()
                if (value.contains('.') && value.length > 20 && value.length < 4000) {
                    qrRunnable?.let { handler.removeCallbacks(it) }
                    val r = Runnable { if (!resolving && screen == Screen.CARD) resolve(value) }
                    qrRunnable = r; handler.postDelayed(r, 250)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        if (card != null || !Cart.isEmpty()) {
            bottomBar.addView(button("К заказу", true, 56) { notice = null; screen = Screen.ORDER; safeRender() },
                LinearLayout.LayoutParams(MATCH, dp(56)).apply { bottomMargin = dp(6) })
            bottomBar.addView(button(if (card != null) "Убрать карту и продолжить" else "Продолжить без карты", false, 48) {
                CardSession.clear(this); notice = null; screen = Screen.ORDER; safeRender()
            }, LinearLayout.LayoutParams(MATCH, dp(48)))
        } else {
            bottomBar.addView(button("Продолжить без карты", true, 56) {
                CardSession.clear(this); notice = null; screen = Screen.ORDER; safeRender()
            }, LinearLayout.LayoutParams(MATCH, dp(56)))
        }
        if (!resolving) field.post {
            if (!isFinishing) {
                field.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun resolve(raw: String) {
        val code = raw.trim().removePrefix("6.7:").removePrefix("67coffee:").trim()
        if (code.isEmpty()) { input?.error = "Введите номер карты или отсканируйте QR"; return }
        if (resolving) return
        resolving = true; notice = null; safeRender()
        thread(name = "loyalty-lookup") {
            val info = LoyaltyApi.resolve(code)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                resolving = false
                if (info.code.isNotEmpty()) {
                    CardSession.set(this, info)
                    lastCardCode = code; lastCardLabel = info.cardCode.ifBlank { null }
                    screen = Screen.ORDER
                    // Причина сбоя/ограничения остаётся видна на экране заказа.
                    if (info.freeStatus == "OFFLINE" && info.note != null) { notice = info.note; noticeKind = Kind.WARN } else notice = null
                } else {
                    CardSession.clear(this)
                    if (info.freeStatus == "ERROR") {
                        notice = info.note ?: "Карта не найдена"; noticeKind = Kind.ERROR
                    } else {
                        notice = (info.note ?: "Нет связи с сервером.") + " Номер карты без связи принять нельзя: отсканируйте QR или продолжите без карты."
                        noticeKind = Kind.WARN
                    }
                }
                safeRender()
            }
        }
    }

    // ── Экран 2: каталог + липкая корзина ──

    private fun renderOrder() {
        val card = CardSession.get(this)?.takeIf { it.code.isNotEmpty() }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(cardChip(card, clickable = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        val more = button("Ещё", false, 56) { }
        more.setOnClickListener { showMore(more, card) }
        head.addView(more, LinearLayout.LayoutParams(dp(80), dp(56)).apply { leftMargin = dp(8) })
        topBar.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        notice?.let { topBar.addView(banner(it, noticeKind).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) }) }

        val search = EditText(this).apply {
            hint = "Поиск по названию"
            isSingleLine = true
            textSize = 16f
            setTextColor(INK); setHintTextColor(HINT)
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = rounded(WHITE, 12, LINE)
            setPadding(dp(12), 0, dp(12), 0)
            setText(query)
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString().orEmpty(); fillCatalog() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        searchView = search
        root.addView(search, LinearLayout.LayoutParams(MATCH, dp(48)).apply { bottomMargin = dp(8) })

        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        catalogHost = host
        root.addView(host, LinearLayout.LayoutParams(MATCH, WRAP))
        fillCatalog()

        val summary = text("", 16f, INK, true)
        itemsView = summary
        val cartBtn = button("Корзина", false, 48) { showCart() }
        val sumRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sumRow.addView(summary, LinearLayout.LayoutParams(0, WRAP, 1f))
        sumRow.addView(cartBtn, LinearLayout.LayoutParams(dp(120), dp(48)))
        bottomBar.addView(sumRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })
        val pay = button("К оплате", true, 56) { showUpsellThenCheckout(card) }
        payButton = pay
        bottomBar.addView(pay, LinearLayout.LayoutParams(MATCH, dp(56)))
        updateBar()
    }


    private fun fillCatalog() {
        val host = catalogHost ?: return
        host.removeAllViews(); badges.clear()
        val searchOn = products.size > SEARCH_MIN_ITEMS
        searchView?.visibility = if (searchOn) View.VISIBLE else View.GONE
        if (products.isEmpty()) {
            when {
                productsLoading -> host.addView(banner("Загружаем каталог…", Kind.INFO))
                productsError -> {
                    host.addView(banner("Каталог не загрузился.", Kind.ERROR))
                    host.addView(button("Повторить", true, 48) { loadProducts() }, LinearLayout.LayoutParams(MATCH, dp(48)))
                }
                else -> host.addView(banner("В каталоге Эвотор нет доступных товаров. Синхронизируйте номенклатуру.", Kind.WARN))
            }
            return
        }
        val groups = products.mapNotNull { it.groupName }.distinct().sorted()
        if (group != null && group !in groups) group = null

        // Чипы категорий — pill как на целевых скринах
        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, dp(4), 0)
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun chip(label: String, value: String?) {
            val on = group == value
            chips.addView(TextView(this).apply {
                this.text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (on) WHITE else INK)
                setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                background = rounded(if (on) BRAND else WHITE, 22, if (on) BRAND else LINE)
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
                minimumHeight = dp(40)
                setOnClickListener { group = value; fillCatalog() }
            }, LinearLayout.LayoutParams(WRAP, dp(40)).apply { rightMargin = dp(8) })
        }
        chip("Всё", null)
        groups.forEach { chip(it, it) }
        strip.addView(chips)
        host.addView(strip, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })

        val q = if (searchOn) query.trim() else ""
        val filtered = products.filter {
            (group == null || it.groupName == group) && (q.isEmpty() || it.name.contains(q, ignoreCase = true))
        }
        if (filtered.isEmpty()) {
            host.addView(text("Ничего не найдено", 15f, HINT, false))
            return
        }

        // Блок «Фокус» — featured / combos / первые напитки
        val focus = filtered.filter { it.featured || it.isCombo }.ifEmpty {
            filtered.filter { it.freeEligible }.take(6)
        }.take(8)
        if (focus.isNotEmpty() && q.isEmpty()) {
            host.addView(TextView(this).apply {
                text = "Фокус"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(INK)
                setPadding(0, 0, 0, dp(6))
            })
            val focusScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
            }
            val focusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            focus.forEach { p ->
                focusRow.addView(focusCard(p), LinearLayout.LayoutParams(dp(124), WRAP).apply { rightMargin = dp(10) })
            }
            focusScroll.addView(focusRow)
            host.addView(focusScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })
        }

        // Секция списка
        val sectionTitle = group ?: "Меню"
        host.addView(TextView(this).apply {
            text = sectionTitle
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            setPadding(0, 0, 0, dp(8))
        })
        filtered.forEach { p ->
            host.addView(listRow(p), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
    }

    /** Круглый «фото»-плейсхолдер с инициалами (URL-фото подключаются позже через Coil). */
    private fun thumb(p: CatalogProduct, sizeDp: Int): TextView {
        val initials = p.name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
            .ifEmpty { "•" }
        return TextView(this).apply {
            text = initials
            textSize = if (sizeDp >= 56) 16f else 13f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(BRAND)
            background = rounded(SOFT, sizeDp / 2, SOFT)
            minWidth = dp(sizeDp)
            minHeight = dp(sizeDp)
        }
    }

    private fun focusCard(p: CatalogProduct): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(WHITE, 16, LINE)
            isClickable = true
            isFocusable = true
            elevation = dp(2).toFloat()
        }
        val photoWrap = FrameLayout(this).apply {
            setPadding(0, 0, 0, dp(8))
            addView(thumb(p, 72), FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER_HORIZONTAL))
        }
        box.addView(photoWrap, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(TextView(this).apply {
            text = p.name
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        })
        box.addView(TextView(this).apply {
            text = rub(p.priceRub)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BRAND)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        })
        val badge = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF1E6B4A.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(badge)
        badges[p.uuid] = badge
        setBadge(p.uuid)
        box.setOnClickListener { onProductTap(p) }
        box.setOnLongClickListener { showRecipe(p); true }
        return box
    }

    private fun listRow(p: CatalogProduct): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(WHITE, 14, LINE)
            minimumHeight = dp(64)
            isClickable = true
            isFocusable = true
        }
        box.addView(thumb(p, 48), LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(10) })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(this).apply {
            text = p.name
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val meta = buildString {
            append(rub(p.priceRub))
            if (p.isCombo) append(" · комбо")
            if (p.freeEligible) append(" · стакан")
        }
        mid.addView(TextView(this).apply {
            text = meta
            textSize = 13f
            setTextColor(HINT)
        })
        val badge = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF1E6B4A.toInt())
        }
        mid.addView(badge)
        badges[p.uuid] = badge
        setBadge(p.uuid)
        box.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(TextView(this).apply {
            text = "i"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(BRAND)
            setTypeface(null, Typeface.BOLD)
            background = rounded(WHITE, 20, BRAND)
            contentDescription = "Рецепт: ${p.name}"
            isClickable = true
            setOnClickListener { showRecipe(p) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) })
        box.setOnClickListener { onProductTap(p) }
        return box
    }

    private fun onProductTap(p: CatalogProduct) {
        if (p.toppings.isNotEmpty()) showToppingsSheet(p)
        else {
            Cart.add(CartLine(p.uuid, p.name, p.priceRub, BigDecimal.ONE, p.recipe, p.freeEligible))
            setBadge(p.uuid); updateBar()
        }
    }

    /** Топинги с backend scheme — перед добавлением напитка в корзину. */
    private fun showToppingsSheet(p: CatalogProduct) {
        val dialog = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(text(p.name, 18f, BRAND, true))
        box.addView(text("Добавки (по желанию)", 14f, HINT, false).apply { setPadding(0, dp(4), 0, dp(8)) })
        val selected = linkedMapOf<Int, Int>()
        val groups = p.toppings.groupBy { it.groupKey }
        groups.forEach { (gk, list) ->
            box.addView(text(gk, 13f, HINT, true).apply { setPadding(0, dp(6), 0, dp(4)) })
            list.forEach { top ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val label = text("${top.name} · ${rub(top.priceRub)}", 15f, INK, false)
                val qty = text("0", 15f, BRAND, true)
                val minus = button("−", false, 40) {
                    val n = (selected[top.id] ?: 0) - 1
                    if (n <= 0) selected.remove(top.id) else selected[top.id] = n
                    qty.text = "${selected[top.id] ?: 0}"
                }
                val plus = button("+", false, 40) {
                    val n = (selected[top.id] ?: 0) + 1
                    if (n <= top.maxCount) {
                        selected[top.id] = n
                        qty.text = "$n"
                    }
                }
                row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(minus, LinearLayout.LayoutParams(dp(40), dp(40)))
                row.addView(qty, LinearLayout.LayoutParams(dp(28), WRAP))
                row.addView(plus, LinearLayout.LayoutParams(dp(40), dp(40)))
                box.addView(row)
            }
        }
        box.addView(button("В заказ", true, 52) {
            Cart.add(CartLine(p.uuid, p.name, p.priceRub, BigDecimal.ONE, p.recipe, p.freeEligible))
            p.toppings.forEach { top ->
                val n = selected[top.id] ?: return@forEach
                if (n <= 0) return@forEach
                val uuid = top.evotorUuid?.takeIf { it.isNotBlank() } ?: "mod:${top.id}"
                Cart.add(CartLine(uuid, "${top.name} (${p.name})", top.priceRub, BigDecimal(n), null, false))
            }
            setBadge(p.uuid); updateBar()
            dialog.dismiss()
        }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(12) })
        box.addView(button("Без добавок", false, 48) {
            Cart.add(CartLine(p.uuid, p.name, p.priceRub, BigDecimal.ONE, p.recipe, p.freeEligible))
            setBadge(p.uuid); updateBar()
            dialog.dismiss()
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(8) })
        val scroll = NestedScrollView(this)
        scroll.addView(box)
        dialog.setContentView(scroll)
        dialog.show()
    }

    private fun setBadge(uuid: String) {
        val b = badges[uuid] ?: return
        val n = Cart.qty(uuid)
        b.text = if (n > 0) "В заказе: ×$n" else ""
        b.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    private fun refreshBadges() { badges.keys.toList().forEach { setBadge(it) } }

    private fun updateBar() {
        val empty = Cart.isEmpty()
        itemsView?.text = if (empty) "Корзина пуста" else "Позиций: ${Cart.count()}"
        payButton?.apply {
            text = if (empty) "Добавьте позицию" else "К оплате · ${rub(Cart.gross())}"
            isEnabled = !empty
            alpha = if (empty) 0.5f else 1f
        }
    }

    private fun cardChip(card: CardInfo?, clickable: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(WHITE, 12, LINE)
            minimumHeight = dp(56)
            gravity = Gravity.CENTER_VERTICAL
        }
        if (card == null) {
            box.addView(text("Без карты", 16f, INK, true))
            if (clickable) box.addView(text("Нажмите, чтобы ввести карту", 14f, HINT, false))
        } else {
            val main = when {
                card.freeStatus == "OFFLINE" -> "Карта по QR · баланс неизвестен"
                else -> "Карта ${card.cardCode.ifBlank { "—" }} · ${card.paidCups}/${card.cupsForFree} · ${card.cashbackKopecks / 100} ₽"
            }
            box.addView(text(main, 16f, BRAND, true))
            when {
                card.freeStatus == "OFFLINE" -> box.addView(text("Нет связи — бонусы недоступны", 14f, 0xFF6B4700.toInt(), true))
                card.freeStatus == "ALREADY_RESERVED" -> box.addView(text("Бонус уже зарезервирован другим чеком", 14f, 0xFF6B4700.toInt(), true))
                card.freeAvailable > 0 -> box.addView(text("Доступен подарок", 14f, 0xFF1E6B4A.toInt(), true))
            }
        }
        if (clickable) { box.isClickable = true; box.setOnClickListener { notice = null; screen = Screen.CARD; safeRender() } }
        return box
    }

    private fun showRecipe(p: CatalogProduct) {
        fun showText(r: String?) {
            if (isFinishing || isDestroyed) return
            AlertDialog.Builder(this).setTitle(p.name).setMessage(r ?: "Рецепт для этой позиции не указан.").setPositiveButton("Закрыть", null).show()
        }
        if (!p.recipe.isNullOrBlank()) { showText(p.recipe); return }
        val ctx = applicationContext
        thread(name = "recipe-load") {
            val r = InventoryRepository.recipeFor(ctx, p.uuid)
            runOnUiThread { showText(r) }
        }
    }

    private fun showMore(anchor: View, card: CardInfo?) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Обновить каталог")
        pm.menu.add(0, 2, 1, "Ввести другую карту")
        if (card != null) pm.menu.add(0, 3, 2, "Сбросить клиента")
        if (!Cart.isEmpty()) pm.menu.add(0, 4, 3, "Очистить корзину")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> loadProducts()
                2 -> { notice = null; screen = Screen.CARD; safeRender() }
                3 -> confirm("Сбросить клиента?", "Карта будет отвязана от заказа, корзина останется.", "Сбросить") { CardSession.clear(this); notice = null; safeRender() }
                4 -> confirm("Очистить корзину?", "Все ${Cart.count()} поз. будут удалены.", "Очистить") { Cart.clear(); refreshBadges(); updateBar() }
            }
            true
        }
        pm.show()
    }

    private fun confirm(title: String, msg: String, action: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(action) { _, _ -> onYes() }
            .setNegativeButton("Отмена", null).show()
    }

    // ── Корзина (bottom sheet) ──

    private fun showCart() {
        dismissSheets()
        val dialog = BottomSheetDialog(this)
        val scroll = NestedScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        scroll.addView(box)
        fun fill() {
            box.removeAllViews()
            box.addView(text("Корзина", 20f, BRAND, true).apply { setPadding(0, 0, 0, dp(8)) })
            val lines = Cart.all()
            if (lines.isEmpty()) {
                box.addView(text("Корзина пуста. Выберите позиции в каталоге.", 16f, HINT, false).apply { setPadding(0, 0, 0, dp(12)) })
            }
            lines.forEach { line ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this).apply {
                    text = "${line.name}\n${line.quantity.stripTrailingZeros().toPlainString()} × ${rub(line.priceRub)} = ${rub(line.lineTotal())}"
                    textSize = 16f; setTextColor(INK)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                fun small(label: String, desc: String, onClick: () -> Unit) {
                    row.addView(Button(this).apply {
                        text = label; textSize = 20f; transformationMethod = null; contentDescription = "$desc: ${line.name}"
                        minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                        setOnClickListener { onClick(); refreshBadges(); updateBar(); fill() }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(4) })
                }
                small("−", "Уменьшить") { Cart.setQty(line.productUuid, line.quantity.subtract(BigDecimal.ONE)) }
                small("+", "Увеличить") { Cart.add(line.copy(quantity = BigDecimal.ONE)) }
                small("×", "Удалить") { Cart.remove(line.productUuid) }
                box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
            }
            if (lines.isNotEmpty()) box.addView(text("Итого: ${rub(Cart.gross())}", 18f, INK, true).apply { setPadding(0, dp(4), 0, dp(12)) })
            box.addView(button("Закрыть", true, 56) { dialog.dismiss() }, LinearLayout.LayoutParams(MATCH, dp(56)))
        }
        fill()
        dialog.setContentView(scroll)
        dialog.setOnDismissListener { refreshBadges(); updateBar(); if (cartDialog === dialog) cartDialog = null }
        cartDialog = dialog
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    // ── Экран 3: «К оплате» с лояльностью ──


    private fun showUpsellThenCheckout(card: CardInfo?) {
        val inCart = Cart.all().map { it.productUuid }.toSet()
        val candidates = LinkedHashMap<String, UpsellItem>()
        products.forEach { p ->
            if (p.uuid in inCart) p.upsell.forEach { u ->
                val id = u.evotorUuid ?: return@forEach
                if (id !in inCart) candidates[id] = u
            }
        }
        upsellDefault.forEach { u ->
            val id = u.evotorUuid ?: return@forEach
            if (id !in inCart) candidates.putIfAbsent(id, u)
        }
        val list = candidates.values.take(5)
        if (list.isEmpty()) {
            checkout(card)
            return
        }
        val dialog = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(text("Добавить к заказу?", 18f, BRAND, true))
        box.addView(text("Рекомендации", 14f, HINT, false).apply { setPadding(0, dp(4), 0, dp(8)) })
        list.forEach { u ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(WHITE, 12, LINE)
            }
            row.addView(text("${u.name}\n${rub(u.priceRub)}", 15f, INK, false), LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(button("+", true, 44) {
                Cart.add(CartLine(u.evotorUuid!!, u.name, u.priceRub, BigDecimal.ONE, null, false))
                updateBar()
                Toast.makeText(this, "Добавлено: ${u.name}", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(dp(56), dp(44)))
            box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        box.addView(button("К оплате", true, 52) {
            dialog.dismiss()
            checkout(card)
        }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(8) })
        box.addView(button("Пропустить", false, 48) {
            dialog.dismiss()
            checkout(card)
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(6) })
        dialog.setContentView(box)
        dialog.show()
    }

    private fun checkout(card: CardInfo?) {
        if (Cart.isEmpty()) { notice = "Корзина пуста — добавьте позиции."; noticeKind = Kind.WARN; safeRender(); return }
        notice = null
        if (card == null) { pay(false, false, 0L); return }
        val now = System.currentTimeMillis() / 1000
        val reserved = DiscountCalc.reserved(card, now)
        val eligibleLines = Cart.all().filter { it.freeEligible }
        val eligiblePrices = eligibleLines.map { it.priceRub }
        val giftPossible = card.freeAvailable > 0 && eligiblePrices.isNotEmpty()
        val cbPossible = card.cashbackKopecks > 0 && card.reservationId != null
        if (!giftPossible && !cbPossible) { pay(false, false, 0L); return }

        dismissSheets()
        val dialog = BottomSheetDialog(this)
        dialog.setCanceledOnTouchOutside(false)   // случайный тап мимо окна ничего не отменяет
        val scroll = NestedScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        scroll.addView(box)

        box.addView(text("К оплате", 20f, BRAND, true).apply { setPadding(0, 0, 0, dp(8)) })
        Cart.all().forEach { l ->
            box.addView(text("${l.name} × ${l.quantity.stripTrailingZeros().toPlainString()} — ${rub(l.lineTotal())}", 16f, INK, false))
        }
        val expiry = card.reservationExpiresAt
        val resText = when {
            card.reservationId == null -> null
            expiry == 0L -> null
            expiry - now > 0 -> "Бонусы зарезервированы ещё ~${(expiry - now + 59) / 60} мин."
            reserved -> "Резерв истёк: бонус применится, только если его не занял другой чек."
            else -> "Резерв истёк — бонус не применится. Отсканируйте карту заново."
        }
        resText?.let { box.addView(banner(it, if (reserved) Kind.INFO else Kind.WARN).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) }) }
        if ((giftPossible || cbPossible) && !reserved) {
            box.addView(banner("Бонус по карте недоступен: резерв не создан или истёк. Оплата пройдёт без скидки.", Kind.WARN))
        }

        var useGift = giftPossible && reserved   // подарок предложен заранее, но выбор явный: «Применить» / «Без подарка»
        var useCb = false                         // по умолчанию «Копить»
        val cups = "${card.paidCups} из ${card.cupsForFree}"
        box.addView(text("Карта ${card.cardCode.ifBlank { "по QR" }} · стаканов $cups · кэшбэк ${card.cashbackKopecks / 100} ₽", 16f, BRAND, true).apply { setPadding(0, dp(8), 0, 0) })
        val totals = text("", 18f, INK, true).apply { setPadding(0, dp(8), 0, dp(12)) }
        val cheapestName = eligibleLines.minByOrNull { it.priceRub }?.name
        val cheapest = DiscountCalc.cheapestEligible(eligiblePrices) ?: BigDecimal.ZERO
        lateinit var recompute: () -> Unit
        fun choice(a: String, b: String, onA: (Boolean) -> Unit) {
            val g = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val ra = RadioButton(this).apply { id = View.generateViewId(); text = a; textSize = 16f; setTextColor(INK); minHeight = dp(48) }
            val rb = RadioButton(this).apply { id = View.generateViewId(); text = b; textSize = 16f; setTextColor(INK); minHeight = dp(48) }
            g.addView(ra); g.addView(rb)
            g.check(ra.id)
            g.setOnCheckedChangeListener { _, id -> onA(id == ra.id); recompute() }
            box.addView(g, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        }
        if (giftPossible && reserved) {
            choice("Применить подарок: «${cheapestName ?: ""}» −${rub(cheapest)}", "Без подарка") { useGift = it }
        } else useGift = false
        if (cbPossible && reserved) {
            // «Копить» (по умолчанию) или «Списать» — кэшбэк списывается только по явному выбору.
            choice("Копить кэшбэк", "Списать кэшбэк (до ${card.cashbackKopecks / 100} ₽)") { useCb = !it }
        }
        box.addView(totals)
        recompute = {
            val gross = Cart.gross()
            val r = DiscountCalc.compute(gross, eligiblePrices, reserved, useGift, card.freeAvailable, if (useCb) card.cashbackKopecks else 0L, card.cashbackKopecks)
            totals.text = "Итого ${rub(gross)}" + (if (r.free.signum() > 0) "\nПодарок −${rub(r.free)}" else "") +
                (if (r.cashback.signum() > 0) "\nКэшбэк −${rub(r.cashback)}" else "") + "\nК оплате: ${rub(gross.subtract(r.total))}"
        }
        recompute()
        box.addView(button("Оплатить", true, 56) {
            dialog.dismiss()
            pay(useGift && reserved, useCb && reserved, if (useCb && reserved) card.cashbackKopecks else 0L)
        }, LinearLayout.LayoutParams(MATCH, dp(56)).apply { bottomMargin = dp(6) })
        box.addView(button("Назад к заказу", false, 48) { dialog.dismiss() }, LinearLayout.LayoutParams(MATCH, dp(48)))

        dialog.setContentView(scroll)
        dialog.setOnDismissListener { if (checkoutDialog === dialog) checkoutDialog = null }
        checkoutDialog = dialog
        dialog.show()
        dialog.behavior.isDraggable = false
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun pay(free: Boolean, spend: Boolean, amount: Long) {
        SellLauncher.openSellReceipt(this, free, spend, amount) { ok, msg ->
            if (ok) {
                PendingPayment.set(this)
                screen = Screen.PAYMENT
                safeRender()
            } else {
                notice = msg; noticeKind = Kind.ERROR
                if (screen == Screen.ORDER) safeRender()
            }
        }
    }

    // ── Экран 4: ожидание подтверждения оплаты ──

    private fun renderPayment() {
        val card = CardSession.get(this)?.takeIf { it.code.isNotEmpty() }
        topBar.addView(text("6.7 Coffee", 22f, BRAND, true))
        root.addView(banner("Чек открыт на оплату. После возврата с экрана оплаты подтвердите результат.", Kind.WARN))
        root.addView(cardChip(card, clickable = false), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        Cart.all().forEach { l ->
            root.addView(text("${l.name} × ${l.quantity.stripTrailingZeros().toPlainString()} — ${rub(l.lineTotal())}", 16f, INK, false))
        }
        root.addView(text("Итого без скидок: ${rub(Cart.gross())}", 18f, INK, true).apply { setPadding(0, dp(8), 0, 0) })
        bottomBar.addView(button("Чек пробит — следующий гость", true, 56) {
            // последняя карта остаётся в памяти процесса для быстрого повтора (lastCardCode)
            CardSession.clear(this); Cart.clear(); PendingPayment.clear(this)
            notice = null; screen = Screen.CARD; safeRender()
        }, LinearLayout.LayoutParams(MATCH, dp(56)).apply { bottomMargin = dp(6) })
        bottomBar.addView(button("Не оплачено — вернуться к заказу", false, 48) {
            PendingPayment.clear(this); notice = null; screen = Screen.ORDER; safeRender()
        }, LinearLayout.LayoutParams(MATCH, dp(48)))
    }

    // ───────────────────────── view-хелперы ─────────────────────────

    private fun text(t: String, sp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun button(label: String, primary: Boolean, heightDp: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 16f; transformationMethod = null; minHeight = dp(heightDp); minimumHeight = dp(heightDp)
        if (primary) { background = rounded(BRAND, 12, BRAND); setTextColor(WHITE) } else { background = rounded(WHITE, 12, BRAND); setTextColor(BRAND) }
        setOnClickListener { onClick() }
    }

    private fun banner(t: String, kind: Kind): TextView {
        val (bg, fg) = when (kind) {
            Kind.ERROR -> 0xFFFDECEA.toInt() to 0xFF8C1D18.toInt()
            Kind.WARN -> 0xFFFFF4D6.toInt() to 0xFF6B4700.toInt()
            Kind.INFO -> 0xFFEEF4FF.toInt() to 0xFF002FA7.toInt()
            Kind.OK -> 0xFFE6F4EC.toInt() to 0xFF1E6B4A.toInt()
        }
        return TextView(this).apply {
            text = t; textSize = 16f; setTextColor(fg); background = rounded(bg, 12, null)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        }
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int?) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun rub(v: BigDecimal): String {
        val s = v.setScale(2, RoundingMode.HALF_UP)
        return (if (s.remainder(BigDecimal.ONE).signum() == 0) s.toBigInteger().toString() else s.toPlainString()) + " ₽"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ───────────────────────── жизненный цикл ─────────────────────────

    override fun onResume() {
        super.onResume()
        try { val f = IntentFilter(ScannerReceiver.ACTION_INTERNAL_SCAN); if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, f, RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(scanReceiver, f) } catch (_: Throwable) {}
        // Вернулись с экрана оплаты: показываем явное подтверждение результата (состояние хранится на диске).
        if (::root.isInitialized && PendingPayment.active(this) && screen != Screen.PAYMENT) { screen = Screen.PAYMENT; safeRender() }
    }

    override fun onPause() { try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}; super.onPause() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.ORDER -> { notice = null; screen = Screen.CARD; safeRender() }
            Screen.PAYMENT -> {}  // из ожидания оплаты выходят только кнопками
            Screen.CARD -> super.onBackPressed()
        }
    }

    private companion object {
        const val TAG = "SixthCup"
        const val SEARCH_MIN_ITEMS = 12
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val BRAND = 0xFF002FA7.toInt()
        val SOFT = 0xFFEEF4FF.toInt()
        val PAGE = 0xFFF5F7FB.toInt()
        val INK = 0xFF1E293B.toInt()
        val HINT = 0xFF475569.toInt()
        val LINE = 0xFFCBD5E1.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
    }
}

