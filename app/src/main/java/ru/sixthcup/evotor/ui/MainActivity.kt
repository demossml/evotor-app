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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import ru.sixthcup.evotor.R
import ru.sixthcup.evotor.data.CardInfo
import ru.sixthcup.evotor.data.CardSession
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CartLine
import ru.sixthcup.evotor.data.CatalogCache
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
    private lateinit var scroll: ScrollView
    private lateinit var orderPane: LinearLayout

    private var products: List<CatalogProduct> = emptyList()
    private var upsellDefault: List<UpsellItem> = emptyList()
    private var productsLoading = false
    private var productsError = false
    private var catalogGen = 0
    private var query = ""
    private var group: String? = null

    private var screen: Screen = Screen.ORDER
    private var resolving = false
    private var input: EditText? = null
    private var notice: String? = null
    private var noticeKind: Kind = Kind.ERROR
    private var lastCardCode: String? = null   // только в памяти процесса, на диск не пишется
    private var lastCardLabel: String? = null

    private var searchView: EditText? = null
    private var chipsScroll: HorizontalScrollView? = null
    private var chipsRow: LinearLayout? = null
    private val chipViews = ArrayList<Pair<String?, TextView>>()
    private var groupsCache: List<String> = emptyList()
    private var recycler: RecyclerView? = null
    private var adapter: CatalogAdapter? = null
    private var searchRunnable: Runnable? = null
    private var lastEnrich: CatalogApi.Payload? = null
    private var payButton: Button? = null
    private var cartButton: Button? = null
    private var cartSidebar: LinearLayout? = null
    private var cartSidebarRows: LinearLayout? = null
    private var cartSidebarTotal: TextView? = null
    private var wideLayout: Boolean = false
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
            scroll = ScrollView(this)
            root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
            scroll.addView(root)
            // Экран заказа: поиск + чипы закреплены, список (RecyclerView) занимает всё остальное.
            orderPane = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), 0); visibility = View.GONE
            }
            bottomBar = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(PAGE); setPadding(dp(12), dp(6), dp(12), dp(6)); elevation = dp(4).toFloat()
            }
            shell.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
            shell.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            shell.addView(orderPane, LinearLayout.LayoutParams(MATCH, 0, 1f))
            shell.addView(bottomBar, LinearLayout.LayoutParams(MATCH, WRAP))
            frame.addView(shell, FrameLayout.LayoutParams(MATCH, MATCH))
            // Системные панели/вырезы: контент не уезжает под них (раньше отступы не учитывались).
            ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
                val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                shell.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(frame)

            screen = when {
                PendingPayment.active(this) -> Screen.PAYMENT
                // Витрина по умолчанию (PWA-like); карта — chip/sheet, не стартовый экран
                else -> Screen.ORDER
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

    /**
     * Двухфазная загрузка: (1) Inventory Эвотор (локально, быстро) → каталог показывается сразу;
     * (2) backend /api/devices/catalog (сеть) → фото, топинги, upsell добавляются поверх без перерисовки всего.
     * Сеть больше не блокирует первый показ каталога.
     */
    private var catalogLoadedAt = 0L
    private var catalogAttemptAt = 0L

    private fun loadProducts() {
        catalogAttemptAt = System.currentTimeMillis()
        val gen = ++catalogGen
        productsLoading = products.isEmpty(); productsError = false
        if (screen == Screen.ORDER) refreshChromeAndRows(false)
        val ctx = applicationContext
        thread(name = "catalog-load") {
            val inv = try { InventoryRepository.load(ctx) } catch (e: Throwable) { Log.e(TAG, "catalog", e); null }
            val known = lastEnrich ?: CatalogCache.load(ctx)
            if (lastEnrich == null && known != null) {
                // restore RAM pointer for subsequent merges this process
            }
            val first = when {
                inv != null && known != null -> CatalogApi.merge(inv, known)
                inv != null -> CatalogApi.applySoftFocus(inv)
                else -> null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || gen != catalogGen) return@runOnUiThread
                productsLoading = false
                // Mark refresh only after local Evotor inventory was actually read successfully.
                if (inv != null) catalogLoadedAt = System.currentTimeMillis()
                if (known != null) lastEnrich = known
                if (first == null) { if (products.isEmpty()) productsError = true } else products = first
                if (screen == Screen.ORDER) refreshChromeAndRows(false)
            }
            if (inv == null) return@thread
            val enrich = try { CatalogApi.fetch(null) } catch (e: Throwable) { Log.e(TAG, "enrich", e); CatalogApi.Payload(emptyMap(), emptyList()) }
            if (enrich.byUuid.isEmpty()) return@thread
            CatalogCache.save(ctx, enrich)
            val merged = CatalogApi.merge(inv, enrich)
            runOnUiThread {
                if (isFinishing || isDestroyed || gen != catalogGen) return@runOnUiThread
                lastEnrich = enrich
                products = merged
                upsellDefault = enrich.upsellDefault
                if (screen == Screen.ORDER) refreshChromeAndRows(false)
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
        root.removeAllViews(); topBar.removeAllViews(); bottomBar.removeAllViews(); orderPane.removeAllViews()
        searchRunnable?.let { handler.removeCallbacks(it) }
        searchView = null; chipsScroll = null; chipsRow = null; chipViews.clear(); groupsCache = emptyList()
        recycler = null; adapter = null; payButton = null; cartButton = null
        cartSidebar = null; cartSidebarRows = null; cartSidebarTotal = null; wideLayout = false
        scroll.visibility = View.VISIBLE; orderPane.visibility = View.GONE
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
        scroll.visibility = View.GONE
        orderPane.visibility = View.VISIBLE
        wideLayout = resources.configuration.screenWidthDp >= 600
        cartSidebar = null; cartSidebarRows = null; cartSidebarTotal = null

        // Карта всегда на виду. Сохраняем компактную шапку для маленького терминала.
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(cardChip(card, clickable = true), LinearLayout.LayoutParams(0, dp(48), 1f))
        val more = button("Ещё", false, 44) { }
        more.setOnClickListener { showMore(more, card) }
        more.textSize = 14f
        head.addView(more, LinearLayout.LayoutParams(dp(58), dp(44)).apply { leftMargin = dp(8) })
        topBar.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        notice?.let { topBar.addView(banner(it, noticeKind, 14f).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) }) }

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val catalogColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        content.addView(catalogColumn, LinearLayout.LayoutParams(0, MATCH, 1f))

        val search = EditText(this).apply {
            hint = "Поиск напитка или десерта"
            isSingleLine = true
            textSize = 15f
            setTextColor(INK); setHintTextColor(HINT)
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = rounded(WHITE, 16, LINE)
            setPadding(dp(14), 0, dp(14), 0)
            setText(query)
            minHeight = dp(48)
            contentDescription = "Поиск по меню"
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                searchRunnable?.let { handler.removeCallbacks(it) }
                val r = Runnable { refreshRows(true) }
                searchRunnable = r
                handler.postDelayed(r, 180)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        searchView = search
        catalogColumn.addView(search, LinearLayout.LayoutParams(MATCH, dp(48)).apply { bottomMargin = dp(8) })

        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        strip.addView(chips)
        chipsScroll = strip; chipsRow = chips
        catalogColumn.addView(strip, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

        val ad = CatalogAdapter(CatalogCallbacks(onTap = { onProductTap(it) }, onInfo = { showRecipe(it) }, onRetry = { loadProducts() }))
        adapter = ad
        val screenWidth = resources.configuration.screenWidthDp
        val columns = if (screenWidth >= 800) 3 else 2
        val rv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, columns).also { gm ->
                gm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val row = ad.currentList.getOrNull(position) ?: return columns
                        return when (row) { is CatalogRow.Item -> 1; else -> columns }
                    }
                }
            }
            adapter = ad
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(8))
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            contentDescription = "Каталог товаров"
        }
        recycler = rv
        catalogColumn.addView(rv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        if (wideLayout) {
            content.addView(buildCartSidebar(), LinearLayout.LayoutParams(dp(if (screenWidth >= 800) 260 else 220), MATCH).apply {
                leftMargin = dp(12)
            })
        }
        orderPane.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        refreshChromeAndRows(false)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cartBtn = button("Корзина пуста", false, 52) { showCart() }
        cartBtn.textSize = 14f; cartBtn.maxLines = 1; cartBtn.ellipsize = TextUtils.TruncateAt.END
        cartButton = cartBtn
        val pay = button("К оплате", true, 52) { showUpsellThenCheckout(card) }
        pay.textSize = 15f; pay.maxLines = 1
        payButton = pay
        row.addView(cartBtn, LinearLayout.LayoutParams(0, dp(52), if (wideLayout) 0f else 1f).apply {
            if (wideLayout) width = 0
        })
        if (wideLayout) cartBtn.visibility = View.GONE
        row.addView(pay, LinearLayout.LayoutParams(if (wideLayout) MATCH else dp(124), dp(52)).apply {
            if (!wideLayout) leftMargin = dp(8)
        })
        bottomBar.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        updateBar()
    }

    /** Постоянная корзина для широких экранов; редактирование остаётся в проверенном bottom sheet. */
    private fun buildCartSidebar(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(WHITE, 20, LINE)
        }
        panel.addView(text("Ваш заказ", 18f, INK, true), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })
        panel.addView(text("Позиции обновляются автоматически", 12f, HINT, false), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rowsScroll = ScrollView(this).apply {
            isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(rows, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        panel.addView(rowsScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val total = text("Итого 0 ₽", 18f, BRAND, true).apply {
            setPadding(0, dp(12), 0, dp(8)); gravity = Gravity.END
        }
        panel.addView(total, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(button("Изменить заказ", false, 48) { showCart() }, LinearLayout.LayoutParams(MATCH, dp(48)))
        cartSidebar = panel; cartSidebarRows = rows; cartSidebarTotal = total
        refreshCartSidebar()
        return panel
    }

    private fun refreshCartSidebar() {
        val rows = cartSidebarRows ?: return
        rows.removeAllViews()
        val lines = Cart.all()
        if (lines.isEmpty()) {
            rows.addView(text("Корзина пока пуста", 15f, HINT, false).apply {
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            lines.forEach { line ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
                row.addView(text(line.name, 14f, INK, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                row.addView(text("×${line.quantity.stripTrailingZeros().toPlainString()}  ·  ${rub(line.lineTotal())}", 14f, HINT, false))
                rows.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
                val divider = View(this).apply { setBackgroundColor(LINE) }
                rows.addView(divider, LinearLayout.LayoutParams(MATCH, dp(1)))
            }
        }
        cartSidebarTotal?.text = "Итого  ${rub(Cart.gross())}"
    }

    /** Поиск/чипы (если набор групп изменился) + строки списка. */
    private fun refreshChromeAndRows(toTop: Boolean) {
        searchView?.visibility = if (products.size > SEARCH_MIN_ITEMS) View.VISIBLE else View.GONE
        // Группы: из Evotor parent + из merge categoryName (6.7).
        val groups = products.mapNotNull { it.groupName?.takeIf { n -> n.isNotBlank() } }
            .distinct()
            .sorted()
        if (groups != groupsCache || chipViews.isEmpty()) {
            groupsCache = groups
            rebuildChips()
        }
        refreshRows(toTop)
    }

    private fun rebuildChips() {
        val row = chipsRow ?: return
        row.removeAllViews(); chipViews.clear()
        if (group != null && group !in groupsCache) group = null
        fun add(label: String, value: String?) {
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
                setOnClickListener {
                    if (group != value) { group = value; updateChipStyles(); refreshRows(true) }
                }
            }
            row.addView(tv, LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(8) })
            chipViews.add(value to tv)
        }
        add("Всё", null)
        groupsCache.forEach { add(it, it) }
        updateChipStyles()
        chipsScroll?.visibility = if (groupsCache.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateChipStyles() {
        for ((value, tv) in chipViews) {
            val on = group == value
            tv.setTextColor(if (on) WHITE else INK)
            tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            tv.background = rounded(if (on) BRAND else WHITE, 21, if (on) BRAND else LINE)
        }
    }

    /** Пересчёт строк в памяти (каталог уже загружен) и diff в адаптер; дерево view не пересобирается. */
    private fun refreshRows(toTop: Boolean) {
        val ad = adapter ?: return
        val rows = computeRows()
        if (toTop) ad.submitList(rows) { recycler?.scrollToPosition(0) } else ad.submitList(rows)
    }

    private fun computeRows(): List<CatalogRow> {
        if (products.isEmpty()) return when {
            productsLoading -> List(6) { CatalogRow.Skeleton(it) }
            productsError -> listOf(CatalogRow.State("err", "Каталог не загрузился.", true, "Повторить"))
            else -> listOf(CatalogRow.State("empty", "В каталоге Эвотор нет доступных товаров. Синхронизируйте номенклатуру.", true, null))
        }
        val q = if (products.size > SEARCH_MIN_ITEMS) query.trim() else ""
        val filtered = products.filter { (group == null || it.groupName == group) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
        if (filtered.isEmpty()) {
            val msg = if (q.isNotEmpty()) "Ничего не найдено по «$q»" else "В этой категории пока нет позиций"
            return listOf(CatalogRow.State("nf", msg, false, null))
        }
        val rows = ArrayList<CatalogRow>(filtered.size + 3)
        if (group == null && q.isEmpty()) {
            val focus = filtered.filter { it.featured }.take(8)
            if (focus.isNotEmpty()) {
                rows.add(CatalogRow.Header("Фокус")); rows.add(CatalogRow.Focus(focus)); rows.add(CatalogRow.Header("Меню"))
            }
        }
        filtered.forEach { rows.add(CatalogRow.Item(it)) }
        return rows
    }

    private fun onProductTap(p: CatalogProduct) {
        // Лист добавок нужен, только если хотя бы одну можно пробить в чек (есть товар Эвотор).
        if (p.toppings.any { !it.evotorUuid.isNullOrBlank() }) showToppingsSheet(p)
        else {
            Cart.add(CartLine(p.uuid, p.name, p.priceRub, BigDecimal.ONE, p.recipe, p.freeEligible))
            onCartChanged()
        }
    }

    /** Топинги с backend scheme — перед добавлением напитка в корзину. */
    private fun showToppingsSheet(p: CatalogProduct) {
        val dialog = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val productPhoto = ThumbView(this).apply { setCorner(18); bind(p.name, p.imageUrl, 480) }
        box.addView(productPhoto, LinearLayout.LayoutParams(MATCH, dp(124)).apply { bottomMargin = dp(12) })
        box.addView(text(p.name, 20f, INK, true))
        box.addView(text("${rub(p.priceRub)} · Настройте напиток", 15f, BRAND, true).apply { setPadding(0, dp(4), 0, dp(8)) })
        val selected = linkedMapOf<Int, Int>()
        val total = text("Итого: ${rub(p.priceRub)}", 18f, BRAND, true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(SOFT, 16, null)
        }
        fun updateModifierTotal() {
            val additions = p.toppings.fold(BigDecimal.ZERO) { sum, top ->
                sum.add(top.priceRub.multiply(BigDecimal(selected[top.id] ?: 0)))
            }
            total.text = "Итого: ${rub(p.priceRub.add(additions))}"
        }
        box.addView(total, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        box.addView(text("Добавки (по желанию)", 14f, HINT, false).apply { setPadding(0, dp(4), 0, dp(8)) })
        val groups = p.toppings.groupBy { it.groupKey }
        groups.forEach { (gk, list) ->
            box.addView(text(toppingGroupLabel(gk), 13f, HINT, true).apply { setPadding(0, dp(6), 0, dp(4)) })
            list.forEach { top ->
                // Без товара Эвотор добавку нельзя пробить в чек — не даём выбрать, чтобы сумма совпадала с чеком.
                val sellable = !top.evotorUuid.isNullOrBlank()
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val label = text(if (sellable) "${top.name} · ${rub(top.priceRub)}" else "${top.name} — нет в кассе Эвотор", 15f, if (sellable) INK else HINT, false)
                val qty = text("0", 15f, BRAND, true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
                val minus = button("−", false, 48) {
                    val n = (selected[top.id] ?: 0) - 1
                    if (n <= 0) selected.remove(top.id) else selected[top.id] = n
                    qty.text = "${selected[top.id] ?: 0}"
                    updateModifierTotal()
                }
                val plus = button("+", false, 48) {
                    val n = (selected[top.id] ?: 0) + 1
                    if (n <= top.maxCount) {
                        selected[top.id] = n
                        qty.text = "$n"
                        updateModifierTotal()
                    }
                }
                if (!sellable) { minus.isEnabled = false; plus.isEnabled = false; minus.alpha = 0.4f; plus.alpha = 0.4f }
                row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(minus, LinearLayout.LayoutParams(dp(48), dp(48)))
                row.addView(qty, LinearLayout.LayoutParams(dp(32), WRAP))
                row.addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)))
                box.addView(row)
            }
        }
        box.addView(button("В чек · добавить", true, 52) {
            val config = selected.filterValues { it > 0 }.toSortedMap()
            val cartKey = if (config.isEmpty()) p.uuid else p.uuid + "#mods:" + config.entries.joinToString(",") { "${it.key}x${it.value}" }
            Cart.add(CartLine(p.uuid, p.name, p.priceRub, BigDecimal.ONE, p.recipe, p.freeEligible, cartKey))
            p.toppings.forEach { top ->
                val n = selected[top.id] ?: return@forEach
                if (n <= 0) return@forEach
                val uuid = top.evotorUuid?.takeIf { it.isNotBlank() } ?: return@forEach
                Cart.add(CartLine(uuid, "${top.name} (${p.name})", top.priceRub, BigDecimal(n), null, false))
            }
            onCartChanged()
            dialog.dismiss()
        }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(12) })
        box.addView(button("Добавить без опций", false, 48) {
            Cart.add(CartLine(p.uuid, p.name, p.priceRub, BigDecimal.ONE, p.recipe, p.freeEligible))
            onCartChanged()
            dialog.dismiss()
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(8) })
        val scroll = NestedScrollView(this)
        scroll.addView(box)
        dialog.setContentView(scroll)
        dialog.show()
    }

    private fun toppingGroupLabel(key: String): String = when (key.lowercase()) {
        "milk" -> "Молоко"
        "syrup" -> "Сиропы"
        "topping" -> "Добавки"
        "extra", "other" -> "Другое"
        else -> key
    }

    /** Корзина изменилась: обновить плашки «×N» (payload, без перебинда) и нижнюю полосу. */
    private fun onCartChanged() { adapter?.refreshQty(); updateBar() }

    private fun updateBar() {
        val empty = Cart.isEmpty()
        cartButton?.text = if (empty) "Корзина пуста" else "Корзина · ${Cart.count()} · ${rub(Cart.gross())}"
        payButton?.apply {
            isEnabled = !empty
            alpha = if (empty) 0.5f else 1f
        }
        cartButton?.apply { isEnabled = !empty; alpha = if (empty) 0.6f else 1f }
        refreshCartSidebar()
    }

    /** Компактная «таблетка» карты: строка 1 — карта/прогресс/кэшбэк, строка 2 — статус (подарок, нет связи). */
    private fun cardChip(card: CardInfo?, clickable: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = rounded(WHITE, 12, LINE)
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
        }
        fun line(t: String, sp: Float, color: Int, bold: Boolean) = text(t, sp, color, bold).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        if (card == null) {
            box.addView(line("6.7 Coffee · без карты", 15f, INK, true))
            if (clickable) box.addView(line("Нажмите, чтобы ввести карту", 12f, HINT, false))
        } else {
            val main = when {
                card.freeStatus == "OFFLINE" -> "Карта по QR · баланс неизвестен"
                else -> "Карта ${card.cardCode.ifBlank { "—" }} · ${card.paidCups}/${card.cupsForFree} · ${card.cashbackKopecks / 100} ₽"
            }
            box.addView(line(main, 14f, BRAND, true))
            when {
                card.freeStatus == "OFFLINE" -> box.addView(line("Нет связи — бонусы недоступны", 12f, 0xFF6B4700.toInt(), true))
                card.freeStatus == "ALREADY_RESERVED" -> box.addView(line("Бонус уже зарезервирован другим чеком", 12f, 0xFF6B4700.toInt(), true))
                card.freeAvailable > 0 -> box.addView(line("Доступен подарок", 12f, 0xFF1E6B4A.toInt(), true))
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
                4 -> confirm("Очистить корзину?", "Все ${Cart.count()} поз. будут удалены.", "Очистить") { Cart.clear(); onCartChanged() }
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
                        setOnClickListener { onClick(); onCartChanged(); fill() }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(4) })
                }
                small("−", "Уменьшить") { Cart.setQty(line.cartKey.ifBlank { line.productUuid }, line.quantity.subtract(BigDecimal.ONE)) }
                small("+", "Увеличить") { Cart.add(line.copy(quantity = BigDecimal.ONE)) }
                small("×", "Удалить") { Cart.remove(line.cartKey.ifBlank { line.productUuid }) }
                box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
            }
            if (lines.isNotEmpty()) box.addView(text("Итого: ${rub(Cart.gross())}", 18f, INK, true).apply { setPadding(0, dp(4), 0, dp(12)) })
            box.addView(button("Закрыть", true, 56) { dialog.dismiss() }, LinearLayout.LayoutParams(MATCH, dp(56)))
        }
        fill()
        dialog.setContentView(scroll)
        dialog.setOnDismissListener { onCartChanged(); if (cartDialog === dialog) cartDialog = null }
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

    /** Защита от двойного нажатия «Оплатить»: второй вызов, пока первый не завершился, игнорируется. */
    private var paying = false

    private fun pay(free: Boolean, spend: Boolean, amount: Long) {
        if (paying) return
        paying = true
        SellLauncher.openSellReceipt(this, free, spend, amount) { ok, msg ->
            paying = false
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
        if (primary) { background = rounded(BRAND, 16, BRAND); setTextColor(WHITE) } else { background = rounded(WHITE, 16, LINE); setTextColor(BRAND) }
        setOnClickListener { onClick() }
    }

    private fun banner(t: String, kind: Kind, sp: Float = 16f): TextView {
        val (bg, fg) = when (kind) {
            Kind.ERROR -> 0xFFFDECEA.toInt() to 0xFF8C1D18.toInt()
            Kind.WARN -> 0xFFFFF4D6.toInt() to 0xFF6B4700.toInt()
            Kind.INFO -> 0xFFEEF4FF.toInt() to 0xFF002FA7.toInt()
            Kind.OK -> 0xFFE6F4EC.toInt() to 0xFF1E6B4A.toInt()
        }
        return TextView(this).apply {
            text = t; textSize = sp; setTextColor(fg); background = rounded(bg, 12, null)
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
        // Каталог/цены не должны «залёживаться» на кассе, которая днями не закрывает приложение.
        if (::root.isInitialized && products.isNotEmpty() && System.currentTimeMillis() - catalogLoadedAt > CATALOG_STALE_MS && System.currentTimeMillis() - catalogAttemptAt > CATALOG_STALE_MS) loadProducts()
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
        const val CATALOG_STALE_MS = 5 * 60 * 1000L
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val BRAND = UiKit.BRAND
        val SOFT = UiKit.SOFT
        val PAGE = UiKit.PAGE
        val INK = UiKit.INK
        val HINT = UiKit.HINT
        val LINE = UiKit.LINE
        val WHITE = UiKit.WHITE
    }
}

