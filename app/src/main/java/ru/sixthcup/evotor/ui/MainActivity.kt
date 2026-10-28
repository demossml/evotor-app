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
import android.widget.ImageView
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import ru.sixthcup.evotor.BuildConfig
import ru.sixthcup.evotor.R
import ru.sixthcup.evotor.data.CardInfo
import ru.sixthcup.evotor.data.CardPoll
import ru.sixthcup.evotor.data.CardProgress
import ru.sixthcup.evotor.data.ResolveVoucher
import ru.sixthcup.evotor.data.VoucherCalc
import ru.sixthcup.evotor.data.CardRefresh
import ru.sixthcup.evotor.data.QueueNumber
import ru.sixthcup.evotor.data.QueueNumberStore
import ru.sixthcup.evotor.data.SalePrintStore
import ru.sixthcup.evotor.data.CardSession
import ru.sixthcup.evotor.net.ShiftApi
import ru.sixthcup.evotor.data.OrderQueueStore
import ru.sixthcup.evotor.data.QueueOps
import ru.sixthcup.evotor.data.QueueOrder
import ru.sixthcup.evotor.data.FoodRules
import ru.sixthcup.evotor.data.Names
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CartLine
import ru.sixthcup.evotor.data.CheckoutChoice
import ru.sixthcup.evotor.data.CatalogCache
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.DiscountCalc
import ru.sixthcup.evotor.data.Combo
import ru.sixthcup.evotor.data.Combos
import ru.sixthcup.evotor.data.DrinkVariants
import ru.sixthcup.evotor.data.InventoryRepository
import ru.sixthcup.evotor.data.MenuTabs
import ru.sixthcup.evotor.data.PendingPayment
import ru.sixthcup.evotor.data.ReceiptIssue
import ru.sixthcup.evotor.data.ReceiptValidator
import ru.sixthcup.evotor.net.DeviceHeaders
import ru.sixthcup.evotor.net.LoyaltyApi
import ru.sixthcup.evotor.net.QueueNumberApi
import ru.sixthcup.evotor.net.CatalogApi
import ru.sixthcup.evotor.net.StoreUuidResolver
import ru.sixthcup.evotor.data.Topping
import ru.sixthcup.evotor.data.UpsellItem
import ru.sixthcup.evotor.scanner.ScannerReceiver
import ru.sixthcup.evotor.sell.SellLauncher
import ru.evotor.framework.receipt.Receipt
import ru.evotor.framework.receipt.ReceiptApi
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.concurrent.thread

/**
 * Кассовый UI 6.7 Coffee (редизайн 4.2.0, см. CHANGELOG-CASHIER-UX.md).
 * Экран = верхняя полоса (карта) + прокручиваемый контент + нижняя полоса (счётчик, сумма чека, «В чек»).
 * Оплаты в APK нет: «В чек» отдаёт позиции Эвотору, дальше POS сам принимает деньги и печатает чек.
 * Никаких новых зависимостей: только платформа, AppCompat, Material (уже подключены).
 */
class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var orderPane: LinearLayout

    private var products: List<CatalogProduct> = emptyList()
    /** Витрина: один напиток = одна плитка, размеры — вариантами внутри item-sheet. */
    private var variantModel: DrinkVariants.Model = DrinkVariants.Model()
    /** Комбо из номенклатуры (товар-определение с `#drink=`/`#food=`). */
    private var combos: List<Combo> = emptyList()
    private var upsellDefault: List<UpsellItem> = emptyList()
    private var productsLoading = false
    private var productsError = false
    private var catalogGen = 0
    private var group: String? = null
    /** UI only (index order-type); not sent to Evotor. */
    private var orderType: String = "С собой"
    /** Рекомендации показываем один раз за заказ (index: state.recShown). */
    private var recShown = false

    private var screen: Screen = Screen.ORDER
    /** shift-page sub-view: home | ops | timesheet | team | stats | breaks | close */
    private var shiftView: String = "home"
    /** Табель: отображаемый месяц (локальный календарь; смены — только с API). */
    private var tsYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    private var tsMonth: Int = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) // 0-based
    private var tsSelectedDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
    /** Пожелания: выбранные дни (локальный черновик до PUT API). */
    private val wishSelected = sortedSetOf<Int>()
    private var wishYear: Int = 0
    private var wishMonth: Int = 0 // 0-based, целевой месяц (обычно следующий)
    private var resolving = false
    private var input: EditText? = null
    private var notice: String? = null
    private var noticeKind: Kind = Kind.ERROR
    private var lastCardCode: String? = null   // только в памяти процесса, на диск не пишется
    private var lastCardLabel: String? = null

    private var chipsScroll: HorizontalScrollView? = null
    private var chipsRow: LinearLayout? = null
    private val chipViews = ArrayList<Pair<String?, TextView>>()
    private var groupsCache: List<String> = emptyList()
    /** Настройка вкладок, под которую построены чипы: меняется — чипы перестраиваем. */
    private var menuCache: CatalogApi.Menu? = null
    private var recycler: RecyclerView? = null
    private var adapter: CatalogAdapter? = null
    private var searchRunnable: Runnable? = null
    private var lastEnrich: CatalogApi.Payload? = null
    private var payButton: Button? = null
    private var cartButton: Button? = null
    private var cartBarRoot: View? = null
    private var emptyCartBarRoot: View? = null
    private var repeatOrderView: View? = null
    private var cartCountView: TextView? = null
    private var cartTotalView: TextView? = null
    private var cartSubView: TextView? = null
    private var cartSidebar: LinearLayout? = null
    private var cartSidebarRows: LinearLayout? = null
    private var cartSidebarTotal: TextView? = null
    private var wideLayout: Boolean = false
    private var cartDockFill: (() -> Unit)? = null
    private var cartDialog: BottomSheetDialog? = null
    private var checkoutDialog: BottomSheetDialog? = null

    private val handler = Handler(Looper.getMainLooper())
    private var qrRunnable: Runnable? = null

    private enum class Screen { CARD, ORDER, SHIFT, QUEUE, POSTPAY }
    private enum class Kind { ERROR, WARN, INFO, OK }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = intent.getStringExtra(ScannerReceiver.EXTRA_CODE).orEmpty().trim()
            if (code.isEmpty() || resolving) return
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
                PendingPayment.active(this) -> Screen.ORDER // оплата только на экране Эвотор; pending обрабатывает onResume
                // Витрина по умолчанию (PWA-like); карта — chip/sheet, не стартовый экран
                else -> Screen.ORDER
            }
            loadProducts()
            safeRender()
            Log.i(TAG, "app_start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) store=${StoreUuidResolver.peek(this)?.take(8)}")
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
    /** Идёт фоновый перезапрос карты — второй параллельный не запускаем. */
    private var cardRefreshing = false
    /** Сколько ждём номер очереди перед открытием чека (спека: 1.0–1.5 с). */
    private val QUEUE_NUMBER_WAIT_MS = 1_500L
    /** Идёт фоновый опрос карты (C04). */
    private var cardPollActive = false
    /** Заказ, только что подтверждённый оплатой: показывается на post-pay экране. */
    private var postPayOrder: QueueOrder? = null
    private val cardPollHandler = Handler(Looper.getMainLooper())
    private var catalogAttemptAt = 0L
    /** Плашка о происхождении каталога: кэш/пустой ответ сервера. null — данные свежие. */
    private var catalogNote: String? = null
    private var swipeRefresh: SwipeRefreshLayout? = null

    private fun setProducts(list: List<CatalogProduct>) {
        products = list
        // Флаг «можно в подарок» мог измениться в админке — обновляем строки корзины.
        Cart.applyEligibility(list.associate { it.uuid to it.freeEligible })
        variantModel = DrinkVariants.build(list)
        combos = Combos.from(list)
        // Диагностика витрины: сколько плиток, сколько строк присоединено к карточкам
        // и сколько дублей объёма отброшено. Раз на обновление каталога, не на кадр.
        Log.i(
            TAG,
            "menu_tiles tiles=${variantModel.tiles.size} variantsLinked=${variantModel.attachedVariants} " +
                "duplicatesDropped=${variantModel.droppedDuplicates} products=${list.size}",
        )
    }

    /**
     * Каталог: (1) номенклатура Эвотор локально → витрина сразу; (2) backend /api/devices/catalog → фото,
     * добавки, upsell. Фоновое обновление — не чаще раза в 60 с; ручное (pull-to-refresh/меню) — всегда.
     * Пустой ответ сервера и сетевые ошибки НЕ затирают кэш: показываем сохранённое с пометкой времени.
     */
    private fun loadProducts(userInitiated: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!userInitiated && now - catalogAttemptAt < CATALOG_REFRESH_MS) return
        catalogAttemptAt = now
        val gen = ++catalogGen
        productsLoading = products.isEmpty(); productsError = false
        if (screen == Screen.ORDER) refreshChromeAndRows(false)
        val ctx = applicationContext
        thread(name = "catalog-load") {
            val startedAt = System.currentTimeMillis()
            val inv = try { InventoryRepository.load(ctx) } catch (e: Throwable) { Log.e(TAG, "catalog", e); null }
            val cachedStore = StoreUuidResolver.peek(ctx)
            val known = lastEnrich ?: CatalogCache.load(ctx, cachedStore)
            val knownAt = CatalogCache.savedAt(ctx, cachedStore)
            val first = when {
                inv != null && known != null -> CatalogApi.merge(inv, known)
                inv != null -> CatalogApi.applySoftFocus(inv)
                else -> null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || gen != catalogGen) return@runOnUiThread
                productsLoading = false
                // Время «свежести» — по последнему реальному ответу (сеть или кэш).
                if (inv != null) catalogLoadedAt = if (knownAt > 0L) knownAt else System.currentTimeMillis()
                if (known != null) {
                    lastEnrich = known
                    upsellDefault = known.upsellDefault
                    if (knownAt > 0L) {
                        catalogNote = "Сохранённые данные от " + hhmm(knownAt)
                        Log.i(TAG, "catalog_cache ageMin=${((System.currentTimeMillis() - knownAt) / 60_000L).coerceAtLeast(0L)}")
                    }
                }
                if (first == null) { if (products.isEmpty()) productsError = true } else setProducts(first)
                CheckoutChoice.saveCombo(this, comboDiscountRub())
                if (screen == Screen.ORDER) refreshChromeAndRows(false)
            }
            if (inv == null) {
                runOnUiThread { swipeRefresh?.isRefreshing = false }
                Log.e(TAG, "catalog_inventory_failed source=ProductTable")
                return@thread
            }
            val storeUuid = StoreUuidResolver.resolve(ctx)
            val res = CatalogApi.fetchDetailed(storeUuid)
            val enrich = res.payload
            if (enrich == null || res.emptyResponse || (enrich.byUuid.isEmpty() && enrich.byName.isEmpty())) {
                runOnUiThread {
                    if (isFinishing || isDestroyed || gen != catalogGen) return@runOnUiThread
                    swipeRefresh?.isRefreshing = false
                    val keep = lastEnrich
                    val at = " от " + hhmm(if (knownAt > 0L) knownAt else System.currentTimeMillis())
                    catalogNote = when {
                        res.emptyResponse -> "Сервер вернул пустой каталог — оставлены сохранённые данные$at"
                        res.httpStatus >= 500 -> "Сервер каталога недоступен (HTTP ${res.httpStatus}) — данные$at"
                        else -> "Нет связи с сервером — данные$at"
                    }
                    if (keep == null && products.isEmpty()) productsError = true
                    val ms = System.currentTimeMillis() - startedAt
                    when {
                        res.httpStatus >= 400 ->
                            Log.e(TAG, "http_error GET /api/devices/catalog code=${res.httpStatus} ms=$ms")
                        res.emptyResponse -> Log.w(TAG, "catalog_empty ms=$ms")
                        else -> Log.w(TAG, "catalog_offline ms=$ms")
                    }
                    if (screen == Screen.ORDER) refreshChromeAndRows(false)
                }
                return@thread
            }
            StoreUuidResolver.remember(ctx, enrich.storeUuid ?: storeUuid)
            CatalogCache.save(ctx, enrich, storeUuid ?: enrich.storeUuid)
            val mergedResult = CatalogApi.mergeWithStats(inv, enrich)
            val merged = mergedResult.first
            val match = mergedResult.second
            val withTop = merged.count { it.toppings.isNotEmpty() }
            val withSellable = merged.count { it.toppings.any { top -> !top.evotorUuid.isNullOrBlank() } }
            runOnUiThread {
                if (isFinishing || isDestroyed || gen != catalogGen) return@runOnUiThread
                swipeRefresh?.isRefreshing = false
                lastEnrich = enrich
                products = merged
                variantModel = DrinkVariants.build(merged)
                combos = Combos.from(merged)
                upsellDefault = enrich.upsellDefault
                catalogNote = null
                catalogLoadedAt = System.currentTimeMillis()
                Log.i(TAG, "enrich ok products=${merged.size} matchedUuid=${enrich.byUuid.size} matchedName=${enrich.byName.size} withToppings=$withTop sellableToppings=$withSellable store=$storeUuid")
                // Диагностика: ответ непустой, но ни один товар не обогащён — обычно значит,
                // что пришёл каталог другой точки (storeUuid не тот или отсутствует).
                val matched = merged.count { it.toppings.isNotEmpty() || it.hideFromMenu }
                if (matched == 0) Log.w(TAG, "enrich merge matched NOTHING store=$storeUuid — toppings will be empty")
                Log.i(
                    TAG,
                    "catalog_match total=${match.total} uuid=${match.byUuid} article=${match.byArticle} " +
                        "exactName=${match.byExactName} name=${match.byName} unmatched=${match.unmatched} " +
                        "hiddenFlag=${match.hiddenByFlag} hiddenCategory=${match.hiddenByCategory} " +
                        "grouped=${match.groupedExplicit} store=${storeUuid ?: "-"}",
                )
                if (match.unmatched > 0) {
                    // Почему товар не сопоставлен: артикул/имя, без секретов. Не больше 3 строк.
                    val sample = merged.asSequence()
                        .filter { it.toppings.isEmpty() && !it.hideFromMenu }
                        .take(3)
                        .map { "${it.article ?: it.uuid}:${it.name}" }
                        .toList()
                    Log.w(TAG, "catalog_unmatched count=${match.unmatched} sample=$sample")
                }
                Log.i(TAG, "catalog_loaded products=${merged.size} withToppings=$withTop ms=${System.currentTimeMillis() - startedAt}")
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
        chipsScroll = null; chipsRow = null; chipViews.clear(); groupsCache = emptyList()
        recycler = null; adapter = null; payButton = null; cartButton = null; cartBarRoot = null; emptyCartBarRoot = null
        repeatOrderView = null; cartCountView = null; cartTotalView = null; cartSubView = null
        cartSidebar = null; cartSidebarRows = null; cartSidebarTotal = null; wideLayout = false
        scroll.visibility = View.VISIBLE; orderPane.visibility = View.GONE
        root.setPadding(dp(12), dp(8), dp(12), dp(16))
        bottomBar.setPadding(dp(12), dp(6), dp(12), dp(6)); bottomBar.setBackgroundColor(PAGE)
        when (screen) {
            Screen.CARD -> renderCard()
            Screen.ORDER -> renderOrder()
            Screen.SHIFT -> renderShift()
            Screen.QUEUE -> renderQueue()
            Screen.POSTPAY -> renderPostPay()
        }
        if (screen != Screen.QUEUE) stopQueueTicker()
        Fonts.apply(topBar); Fonts.apply(root); Fonts.apply(orderPane); Fonts.apply(bottomBar)
        topBar.visibility = if (topBar.childCount == 0) View.GONE else View.VISIBLE
        bottomBar.visibility = if (bottomBar.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun dismissSheets() {
        try { cartDialog?.dismiss() } catch (_: Throwable) {}
        try { checkoutDialog?.dismiss() } catch (_: Throwable) {}
        cartDialog = null; checkoutDialog = null
        if (screen != Screen.CARD) {
            val g = guestDialog
            guestDialog = null; guestBody = null
            try { g?.dismiss() } catch (_: Throwable) {}
        }
    }

    // ── Экран 1: карта (guest-sheet 1:1 index) ──

    private var guestDialog: BottomSheetDialog? = null
    private var guestBody: LinearLayout? = null

    /** Экран CARD = витрина под листом «Карта гостя» (`guest-sheet` в index). */
    private fun renderCard() {
        renderOrder()
        // Открыли карту гостя — один раз подтягиваем свежее состояние с сервера.
        if (CardSession.get(this)?.code?.isNotEmpty() == true) refreshCardAsync()
        val existing = guestDialog
        if (existing != null && existing.isShowing) { fillGuest(); return }
        openGuestSheet()
    }

    private fun openGuestSheet() {
        val dialog = BottomSheetDialog(this)
        guestDialog = dialog
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(WHITE) }
        sheetHead(root, "Карта гостя", "Лояльность 6.7 · чашки и бонусы") { closeGuestSheet() }
        val scroll = NestedScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(16)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        guestBody = body
        dialog.setContentView(root)
        styleSheet(dialog)
        dialog.setOnDismissListener {
            if (guestDialog === dialog) {
                guestDialog = null; guestBody = null
                if (screen == Screen.CARD) { screen = Screen.ORDER; notice = null; safeRender() }
            }
        }
        fillGuest()
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    private fun closeGuestSheet() { guestDialog?.dismiss() }

    private fun fillGuest() {
        val body = guestBody ?: return
        body.removeAllViews()
        val card = CardSession.get(this)?.takeIf { it.code.isNotEmpty() }

        if (resolving) body.addView(banner("Проверяем карту…", Kind.INFO, 11f), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        notice?.let { body.addView(banner(it, noticeKind, 11f), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }) }

        if (card != null) {
            // .guest-card
            val gc = FrameLayout(this).apply { background = rounded(BRAND, 18, null) }
            gc.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.logo_67_mark); alpha = 0.08f
                setColorFilter(WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            }, FrameLayout.LayoutParams(dp(162), dp(165), Gravity.BOTTOM or Gravity.END).apply { rightMargin = -dp(18); bottomMargin = -dp(28) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(15), dp(15), dp(15)) }
            col.addView(TextView(this).apply {
                text = "6.7 CLUB · КАРТА ГОСТЯ"; textSize = 10f; weight(Fonts.SEMIBOLD); letterSpacing = 0.16f; setTextColor(0x9EFFFFFF.toInt())
            })
            col.addView(TextView(this).apply {
                text = card.cardCode.ifBlank { "Гость" }; textSize = 21f; weight(Fonts.STRONG); letterSpacing = -0.025f; setTextColor(WHITE)
                setPadding(0, dp(4), 0, 0)
            })
            if (card.freeStatus == "OFFLINE") col.addView(TextView(this).apply {
                text = "Нет связи · баланс неизвестен"; textSize = 10f; setTextColor(0xADFFFFFF.toInt())
            })
            val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, 0) }
            fun stat(label: String, value: String, first: Boolean) {
                val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                c.addView(TextView(this).apply { text = label; textSize = 10f; letterSpacing = 0.08f; setTextColor(0x8CFFFFFF.toInt()) })
                c.addView(TextView(this).apply { text = value; textSize = 19f; weight(Fonts.STRONG); letterSpacing = -0.02f; setTextColor(WHITE); setPadding(0, dp(2), 0, 0) })
                stats.addView(c, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (!first) leftMargin = dp(22) })
            }
            // Прогресс и «подарок» считает CardProgress: для ручного кода сервер скрывает льготы,
            // поэтому показываем то, что известно, и не обещаем списание.
            val progress = CardProgress.of(card)
            stat("ЧАШКИ", "${progress.cups} / ${progress.goal}", true)
            stat("БОНУСЫ", if (progress.cashbackKnown) "${progress.cashbackKopecks / 100}" else "—", false)
            col.addView(stats)
            col.addView(UiKit.loyaltyDots(this, progress.cups, progress.goal, onColor = 0xFFF6F3EE.toInt()).apply {
                // .gc-progress .lc-dots i: высота 7
                for (i in 0 until childCount) getChildAt(i).layoutParams.height = dp(7)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
            col.addView(TextView(this).apply {
                text = cardProgressLine(progress, inReceipt = true)
                textSize = 10f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, dp(7), 0, 0)
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
            actions.addView(TextView(this).apply {
                text = "К заказу"; textSize = 10f; weight(Fonts.SEMIBOLD); gravity = Gravity.CENTER; setTextColor(INK)
                setPadding(dp(12), 0, dp(12), 0); background = rounded(0xFFF6F3EE.toInt(), 9, null); isClickable = true
                setOnClickListener { notice = null; screen = Screen.ORDER; safeRender() }
            }, LinearLayout.LayoutParams(WRAP, dp(36)).apply { rightMargin = dp(6) })
            actions.addView(TextView(this).apply {
                text = "Отвязать"; textSize = 10f; weight(Fonts.SEMIBOLD); gravity = Gravity.CENTER; setTextColor(WHITE)
                setPadding(dp(12), 0, dp(12), 0); background = rounded(0x24FFFFFF.toInt(), 9, null); isClickable = true
                setOnClickListener { CardSession.clear(this@MainActivity); notice = null; screen = Screen.ORDER; safeRender() }
            }, LinearLayout.LayoutParams(WRAP, dp(36)))
            col.addView(actions)
            gc.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
            body.addView(gc, LinearLayout.LayoutParams(MATCH, WRAP))
            body.addView(noteRow("Бонусы списываются на этот чек. Чашки начисляются после оплаты — по одной за каждый напиток."),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
            Fonts.apply(body)
            return
        }

        // .qr-zone — видоискатель сканера (QR гостя считывает сканер кассы)
        val qr = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(18), dp(12), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(UiKit.SURFACE_2); cornerRadius = dp(16).toFloat()
                setStroke(dp(1), UiKit.LINE_STRONG, dp(4).toFloat(), dp(3).toFloat())
            }
        }
        qr.addView(FrameLayout(this).apply {
            background = rounded(WHITE, 12, null); Ds.shadowS(this)
            addView(icon(this@MainActivity, R.drawable.ic_scan, 78f, INK), FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(134), dp(134)))
        qr.addView(TextView(this).apply { text = "Поднесите карту гостя"; textSize = 11.5f; weight(Fonts.SEMIBOLD); setTextColor(INK); setPadding(0, dp(10), 0, 0) })
        qr.addView(TextView(this).apply { text = "QR в PWA 6.7 Coffee · или введите номер карты"; textSize = 10f; setTextColor(UiKit.MUTED); gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) })
        body.addView(qr, LinearLayout.LayoutParams(MATCH, WRAP))

        // .guest-divider
        val div = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, dp(10)) }
        div.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(0, dp(1), 1f))
        div.addView(TextView(this).apply { text = "ИЛИ ПО НОМЕРУ"; textSize = 10f; letterSpacing = 0.11f; weight(Fonts.SEMIBOLD); setTextColor(UiKit.MUTED) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10); rightMargin = dp(10) })
        div.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(0, dp(1), 1f))
        body.addView(div)

        // .phone-field
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val field = EditText(this).apply {
            hint = "Номер карты или QR"
            setRawInputType(InputType.TYPE_CLASS_NUMBER)
            isSingleLine = true
            textSize = 12f
            setTextColor(INK)
            setHintTextColor(UiKit.MUTED)
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            isEnabled = !resolving
            background = rounded(WHITE, 11, LINE)
            setPadding(dp(12), 0, dp(12), 0)
        }
        input = field
        row.addView(field, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(FrameLayout(this).apply {
            background = rounded(INK, 11, null); isClickable = true; contentDescription = "Найти гостя"
            addView(icon(this@MainActivity, R.drawable.ic_search, 17f, WHITE), FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
            setOnClickListener { if (!resolving) resolve(field.text.toString()) }
        }, LinearLayout.LayoutParams(dp(46), dp(44)).apply { leftMargin = dp(7) })
        body.addView(row)

        val last = lastCardCode
        if (last != null && !resolving) {
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9)); background = rounded(WHITE, 12, LINE); isClickable = true
                setOnClickListener { resolve(last) }
                addView(TextView(this@MainActivity).apply {
                    text = (lastCardLabel ?: last).take(1).uppercase(); textSize = 11f; weight(Fonts.STRONG); gravity = Gravity.CENTER
                    setTextColor(BRAND); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(UiKit.BRAND_SOFT) }
                }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(9) })
                val t = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                t.addView(TextView(this@MainActivity).apply { text = "Последняя: ${lastCardLabel ?: last.take(12)}"; textSize = 10.5f; weight(Fonts.SEMIBOLD); setTextColor(INK) })
                t.addView(TextView(this@MainActivity).apply { text = "Нажмите, чтобы привязать снова"; textSize = 10f; setTextColor(UiKit.MUTED) })
                addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(icon(this@MainActivity, R.drawable.ic_chevron_sm, 12f, UiKit.MUTED), LinearLayout.LayoutParams(dp(12), dp(12)))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        body.addView(noteRow("Карта гостя одна для кассы и PWA: чашки, бонусы и подарки считаются одинаково."),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        body.addView(btn("Продолжить без карты", "ghost") { CardSession.clear(this); notice = null; screen = Screen.ORDER; safeRender() },
            LinearLayout.LayoutParams(MATCH, dp(50)).apply { topMargin = dp(14) })

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
        Fonts.apply(body)
    }

    /** `.cart-note`: значок i 12 + текст 9→10. */
    private fun noteRow(t: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(icon(this@MainActivity, R.drawable.ic_info, 12f, UiKit.MUTED), LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(5); topMargin = dp(1) })
        addView(TextView(this@MainActivity).apply { text = t; textSize = 10f; setTextColor(UiKit.MUTED); setLineSpacing(0f, 1.2f) }, LinearLayout.LayoutParams(0, WRAP, 1f))
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
                    Log.i(TAG, "card_resolved kind=${CardSession.kindOf(code)} free=${info.freeAvailable} cb=${info.cashbackKopecks} reserved=${info.reservationId != null}")
                    lastCardCode = code; lastCardLabel = info.cardCode.ifBlank { null }
                    screen = Screen.ORDER
                    // Причина сбоя/ограничения остаётся видна на экране заказа.
                    when {
                        info.freeStatus == "OFFLINE" && info.note != null -> {
                            notice = info.note; noticeKind = Kind.WARN
                        }
                        info.freeStatus == "ALREADY_RESERVED" -> {
                            notice = "Карта уже зарезервирована на другой кассе. Завершите там чек или подождите."
                            noticeKind = Kind.WARN
                        }
                        else -> notice = null
                    }
                } else {
                    CardSession.clear(this)
                    if (info.freeStatus == "ERROR") {
                        Log.e(TAG, "resolve_failed kind=${CardSession.kindOf(code)} code=${info.errorCode ?: "unknown"} reason=${info.note}")
                    } else {
                        Log.w(TAG, "resolve_offline kind=${CardSession.kindOf(code)}")
                    }
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
        val m = Ds.metrics(this)
        scroll.visibility = View.GONE
        orderPane.visibility = View.VISIBLE
        orderPane.setPadding(0, 0, 0, 0)
        wideLayout = m.mode == Ds.Mode.M10
        cartSidebar = null; cartSidebarRows = null; cartSidebarTotal = null; cartDockFill = null

        // .pos-head
        topBar.setPadding(dp(m.headPadH), dp(m.headPadTop), dp(m.headPadH), dp(m.headPadBottom))
        topBar.addView(buildPosHead(card), LinearLayout.LayoutParams(MATCH, WRAP))
        notice?.let { topBar.addView(banner(it, noticeKind, 12f).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) }) }
        catalogNote?.let {
            topBar.addView(banner(it, Kind.WARN, 11f).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) })
        }

        val stage = FrameLayout(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val catalogColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(catalogColumn, LinearLayout.LayoutParams(0, MATCH, 1f))

        // .search + .scan-btn  (строка прокручивается вместе со списком, как .sale-scroll)

        // .cats
        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(m.sidePad), 0, dp(m.sidePad), dp(2))
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        strip.addView(chips)
        chipsScroll = strip; chipsRow = chips
        searchHost = null; chipsHost = strip

        val ad = CatalogAdapter(CatalogCallbacks(
            onTap = { onProductTap(it) },
            onInfo = { showRecipe(it) },
            onRetry = { loadProducts() },
            onInc = { prod ->
                // Комбо нельзя добавить одной позицией: набор собирается из реальных товаров.
                val combo = combos.firstOrNull { it.id == prod.uuid }
                if (combo != null) showComboSheet(combo)
                else if (variantModel.hasChoice(prod)) showItemSheet(prod)
                else {
                    Cart.add(CartLine(prod.uuid, prod.name, prod.priceRub, BigDecimal.ONE, prod.recipe, prod.freeEligible))
                    onCartChanged()
                }
            },
            onDec = { prod ->
                if (combos.none { it.id == prod.uuid }) {
                    Cart.decAny(variantModel.variantsOf(prod).map { it.uuid })
                    onCartChanged()
                }
            },
            qtyOf = { prod -> Cart.qtyAny(variantModel.variantsOf(prod).map { it.uuid }) },
        ))
        adapter = ad
        val columns = m.cols
        val rv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, columns).also { gm ->
                gm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val row = ad.currentList.getOrNull(position) ?: return columns
                        return when (row) { is CatalogRow.Item, is CatalogRow.Skeleton -> 1; else -> columns }
                    }
                }
            }
            adapter = ad
            addItemDecoration(TileGridDecoration(columns, dp(m.sidePad), dp(m.gap)))
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            // .sale-scroll: padding-bottom 96 — чтобы корзина-бар не закрывала последний ряд
            setPadding(0, dp(4), 0, dp(if (m.mode == Ds.Mode.M10) 24 else 96))
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            contentDescription = "Каталог товаров"
        }
        recycler = rv
        // Pull-to-refresh: ручное обновление каталога (п.6 контракта) без перезапуска приложения.
        val swipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(UiKit.BRAND, INK)
            setProgressBackgroundColorSchemeColor(WHITE)
            setOnRefreshListener { loadProducts(userInitiated = true) }
            addView(rv, ViewGroup.LayoutParams(MATCH, MATCH))
        }
        swipeRefresh = swipe
        catalogColumn.addView(swipe, LinearLayout.LayoutParams(MATCH, 0, 1f))

        if (wideLayout) {
            // Закреплённый чек справа 372 (`.mode-10 #cartOverlay .sheet`).
            val (panel, fill) = buildCartPanel(true) { }
            cartDockFill = fill
            val dock = FrameLayout(this).apply {
                background = leftBorder(WHITE)
                addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            content.addView(dock, LinearLayout.LayoutParams(dp(m.cartDockW), MATCH))
        }
        stage.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        // .cartbar / .empty-cartbar плавают поверх списка
        if (!wideLayout) {
            val bar = buildCartBar(card)
            stage.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                leftMargin = dp(m.barInset); rightMargin = dp(m.barInset); bottomMargin = dp(m.barBottom)
            })
        }
        orderPane.addView(stage, LinearLayout.LayoutParams(MATCH, 0, 1f))
        bottomBar.setPadding(0, 0, 0, 0)
        bottomBar.setBackgroundColor(PAGE)
        bottomBar.elevation = 0f
        refreshChromeAndRows(false)
        updateBar()
    }

    private var searchHost: View? = null
    private var chipsHost: View? = null
    private var searchQuery: String = ""
    private var searchEdit: EditText? = null

    /** `.searchrow`: поле 48 (радиус 14, лупа 17, ✕ 24) + кнопка скана 48. */
    private fun buildSearchRow(m: Ds.Metrics): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(m.sidePad), 0, dp(m.sidePad), 0)
        }
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(WHITE, 14, LINE)
            Ds.shadowS(this)
        }
        field.addView(icon(this, R.drawable.ic_search, 17f, UiKit.MUTED), LinearLayout.LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(9) })
        val edit = EditText(this).apply {
            hint = "Найти напиток"
            setHintTextColor(UiKit.MUTED)
            setTextColor(INK)
            textSize = 12.5f
            background = null
            setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            maxLines = 1
            isSingleLine = true
            setText(searchQuery)
        }
        searchEdit = edit
        val clear = FrameLayout(this).apply {
            background = rounded(UiKit.SURFACE_2, 12, null)
            visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE
            contentDescription = "Очистить поиск"
            addView(icon(this@MainActivity, R.drawable.ic_close, 10f, UiKit.MUTED), FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
            setOnClickListener { edit.setText("") }
        }
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString().orEmpty()
                clear.visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE
                searchRunnable?.let { handler.removeCallbacks(it) }
                val r = Runnable { refreshRows(false) }
                searchRunnable = r
                handler.postDelayed(r, 120)
            }
        })
        edit.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else false
        }
        field.addView(edit, LinearLayout.LayoutParams(0, WRAP, 1f))
        field.addView(clear, LinearLayout.LayoutParams(dp(24), dp(24)))
        row.addView(field, LinearLayout.LayoutParams(0, dp(48), 1f))
        val scan = FrameLayout(this).apply {
            background = rounded(WHITE, 14, LINE)
            isClickable = true
            isFocusable = true
            contentDescription = "Сканировать карту гостя"
            Ds.shadowS(this)
            addView(icon(this@MainActivity, R.drawable.ic_scan, 21f, INK), FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
            setOnClickListener { notice = null; screen = Screen.CARD; safeRender() }
        }
        row.addView(scan, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        return row
    }

    private fun leftBorder(color: Int): android.graphics.drawable.Drawable {
        val line = android.graphics.drawable.ColorDrawable(LINE)
        val fill = android.graphics.drawable.ColorDrawable(color)
        return android.graphics.drawable.LayerDrawable(arrayOf(line, fill)).apply { setLayerInset(1, dp(1), 0, 0, 0) }
    }

    private fun refreshCartSidebar() { cartDockFill?.invoke() }

    /** Поиск/чипы (если набор групп изменился) + строки списка. */
    private fun refreshChromeAndRows(toTop: Boolean) {
        // Группы: из Evotor parent + из merge categoryName (6.7).
        val labels = products.filter { !it.hideFromMenu }.mapNotNull { it.groupName?.takeIf { n -> n.isNotBlank() } }
            .distinct()
        // Порядок и чип «Всё» — из админки (`menu`); без настройки — алфавит и «Всё», как раньше.
        val menu = lastEnrich?.menu
        val groups = MenuTabs.order(labels, products, menu)
        if (groups != groupsCache || menu != menuCache || chipViews.isEmpty()) {
            groupsCache = groups
            menuCache = menu
            rebuildChips()
        }
        refreshRows(toTop)
    }

    /** cats + .chip 1:1 index: height 38, pill, selected = ink (не brand). */
    private fun rebuildChips() {
        val row = chipsRow ?: return
        row.removeAllViews(); chipViews.clear()
        if (group == GROUP_COMBOS && combos.isEmpty()) group = null
        if (group != null && group != GROUP_COMBOS && group !in groupsCache) group = null
        // Без чипа «Всё» список не должен остаться «без выбранной вкладки»: открываем первую группу.
        if (group == null && groupsCache.isNotEmpty() && !MenuTabs.showAll(lastEnrich?.menu)) {
            group = groupsCache.first()
        }
        fun add(label: String, value: String?) {
            val tv = TextView(this).apply {
                text = label
                textSize = 11.5f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(14), 0, dp(14), 0)
                weight(Fonts.MEDIUM)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (group != value) { group = value; updateChipStyles(); refreshRows(true) }
                }
            }
            row.addView(tv, LinearLayout.LayoutParams(WRAP, dp(38)).apply { rightMargin = dp(6) })
            chipViews.add(value to tv)
        }
        if (MenuTabs.showAll(lastEnrich?.menu)) add("Всё", null)
        if (combos.isNotEmpty()) add("Комбо", GROUP_COMBOS)
        groupsCache.forEach { add(it, it) }
        updateChipStyles()
        chipsScroll?.visibility =
            if (groupsCache.isEmpty() && combos.isEmpty()) View.GONE else View.VISIBLE
        Fonts.apply(row)
    }

    private fun updateChipStyles() {
        for ((value, tv) in chipViews) {
            val on = group == value
            // index .chip.on → background: var(--ink); color: #fff
            tv.setTextColor(if (on) WHITE else UiKit.INK_2)
            tv.background = rounded(if (on) INK else WHITE, 999, if (on) INK else LINE)
        }
    }

    /** Пересчёт строк в памяти (каталог уже загружен) и diff в адаптер; дерево view не пересобирается. */
    private fun refreshRows(toTop: Boolean) {
        val ad = adapter ?: return
        val rows = computeRows()
        if (toTop) ad.submitList(rows) { recycler?.scrollToPosition(0) } else ad.submitList(rows)
    }

    /**
     * Одна короткая фраза, объясняющая кассиру состояние льгот (блок C).
     * Возвращает null, если объяснять нечего.
     */
    private fun loyaltyHint(card: CardInfo, progress: CardProgress): String? {
        val reserved = DiscountCalc.reserved(card, System.currentTimeMillis() / 1000)
        val eligible = Cart.all().any { it.freeEligible }
        return when {
            card.freeStatus == "ALREADY_RESERVED" ->
                "Подарок уже зарезервирован на другой кассе. Завершите там чек или подождите."
            card.freeStatus == "OFFLINE" ->
                "Нет связи: подарок и кэшбэк недоступны, чашки начислятся после чека."
            progress.giftAvailable && !reserved && progress.kind == "code" ->
                "Для списания подарка нужен QR клиента: по номеру карты подарок не списывается."
            progress.giftAvailable && reserved && !eligible ->
                "Нет подходящего напитка в чеке для подарка."
            progress.giftAvailable && reserved && eligible ->
                "Подарок доступен — примените в листе «Лояльность»."
            progress.cashbackKnown && progress.cashbackKopecks > 0L && !reserved ->
                "Кэшбэк спишется только по QR клиента."
            else -> null
        }
    }

    /**
     * Подпись под прогрессом карты. Никогда не обещает списание, которого не будет:
     * по ручному коду сервер скрывает льготы, поэтому там — «по QR-карте».
     */
    private fun cardProgressLine(progress: CardProgress, inReceipt: Boolean): String = when {
        !progress.giftKnown && !progress.giftAvailable -> "Бонусы и подарок — по QR-карте"
        progress.redeemEnabled && inReceipt -> "Бесплатный напиток доступен в этом чеке"
        progress.giftAvailable && progress.kind == "code" -> "Подарок доступен · списание по QR-карте"
        progress.giftAvailable -> "Подарок доступен"
        else -> "Ещё ${progress.left} ${plural(progress.left, "чашка", "чашки", "чашек")} до бесплатного напитка"
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100; val m10 = n % 10
        return when {
            m100 in 11..14 -> many
            m10 == 1 -> one
            m10 in 2..4 -> few
            else -> many
        }
    }

    /** `.tile-meta`: «3 размера · молоко» для напитка с выбором, иначе категория. */
    private fun tileMeta(p: CatalogProduct): String {
        if (p.isCombo) return "Напиток + выпечка"
        val sizes = variantModel.variantsOf(p).size
        val parts = ArrayList<String>(2)
        if (sizes > 1) parts.add("$sizes ${plural(sizes, "размер", "размера", "размеров")}")
        if (p.supportsMods && (!variantModel.options.milk.isEmpty() || p.toppings.any { it.groupKey.equals("milk", true) })) parts.add("молоко")
        if (parts.isEmpty()) return p.groupName.orEmpty()
        return parts.joinToString(" · ")
    }

    private fun computeRows(): List<CatalogRow> {
        val hostRows = ArrayList<CatalogRow>(8)
        val q = "" // поиск убран с витрины
        if (products.isEmpty()) {
            val st: List<CatalogRow> = when {
                productsLoading -> List(6) { CatalogRow.Skeleton(it) }
                productsError -> listOf(CatalogRow.State("err", "Каталог не загрузился.", true, "Повторить"))
                else -> listOf(CatalogRow.State("empty", "В каталоге Эвотор нет доступных товаров. Синхронизируйте номенклатуру.", true, null))
            }
            return hostRows + st
        }
        val tiles = variantModel.tiles
        val comboTiles = if (combos.isNotEmpty() && q.isEmpty() && (group == null || group == GROUP_COMBOS))
            combos.map { Combos.tile(it, products) } else emptyList()
        val filtered = if (group == GROUP_COMBOS) emptyList() else tiles.filter {
            (group == null || it.groupName == group) && (q.isEmpty() || it.name.contains(q, ignoreCase = true))
        }
        val rows = ArrayList<CatalogRow>(filtered.size + comboTiles.size + 8)
        rows.addAll(hostRows)
        if (group == null && q.isEmpty()) {
            val focus = filtered.filter { it.featured }.take(8)
            if (focus.isNotEmpty()) {
                rows.add(CatalogRow.Header("Сейчас берут"))
                rows.add(CatalogRow.Focus(focus))
            }
        }
        chipsHost?.let { rows.add(CatalogRow.Host("cats", it, 12)) }
        val total = filtered.size + comboTiles.size
        val title = when {
            q.isNotEmpty() -> "Результаты"
            group == GROUP_COMBOS -> "Комбо"
            group == null -> "Всё меню"
            else -> group!!
        }
        rows.add(CatalogRow.MenuHead(title, "$total ${plural(total, "позиция", "позиции", "позиций")}"))
        if (total == 0) {
            rows.add(CatalogRow.State("nf", if (q.isNotEmpty()) "Ничего не нашли по «$q»" else "В этой категории пока нет позиций", false, null))
            return rows
        }
        comboTiles.forEach { rows.add(CatalogRow.Item(it, tileMeta(it))) }
        filtered.forEach { rows.add(CatalogRow.Item(it, tileMeta(it))) }
        return rows
    }

    private fun onProductTap(p: CatalogProduct) {
        combos.firstOrNull { it.id == p.uuid }?.let { showComboSheet(it); return }
        // Есть выбор объёма/молока/сиропа — открываем item-sheet; иначе сразу в чек.
        if (variantModel.hasChoice(p)) showItemSheet(p)
        else {
            Cart.add(CartLine(p.uuid, p.name, p.priceRub, BigDecimal.ONE, p.recipe, p.freeEligible))
            onCartChanged()
        }
    }
    /**
     * item-sheet 1:1 index: hero → название+цена → описание → теги → «в чеке уже N» →
     * группы опций (Объём / Молоко / Сироп / Сахар / Добавки) → qty + «В чек · сумма».
     *
     * Цена каждой опции — PRICE_OUT реального товара Эвотор (никаких дельт на клиенте):
     * иначе чек разойдётся с номенклатурой, а склад модификаторов не спишется.
     * Опция без evotorUuid и с ценой > 0 недоступна — её нельзя провести фискально.
     */
    /**
     * Схема опций: приоритет — товары Эвотор с метками `#opt=`;
     * если их нет, берём топпинги из `/api/devices/catalog` (совместимость с текущим каталогом).
     */
    /** backend `productId` по uuid терминала — для короткого meta в журнале. */
    private fun productIdOf(uuid: String): Int? =
        lastEnrich?.byUuid?.get(uuid)?.productId?.takeIf { it > 0 }

    private fun optionsFor(p: CatalogProduct): OptionPicker.Opts {
        val evotor = variantModel.options
        // maxCount у опций из номенклатуры не задан: milk/syrup/sugar — одиночный выбор,
        // extra — «можно несколько» (лимит backend-схемы применяется к её же опциям).
        fun mapEv(list: List<CatalogProduct>, max: Int = 1) = list.map {
            OptionPicker.Opt(it.uuid, it.optionLabel, it.priceRub, it.uuid, it.defaultVariant, maxCount = max)
        }
        // Одна точка сборки: поля схемы и `Topping.id` (нужен ReceiptValidator) не расходятся.
        fun fromTopping(t: Topping) = OptionPicker.Opt(
            key = "t:${t.id}",
            label = t.name,
            price = t.priceRub,
            evotorUuid = t.evotorUuid?.takeIf { u -> u.isNotBlank() },
            isDefault = t.isDefault,
            required = t.required,
            zeroOption = t.zeroOption,
            available = t.available,
            maxCount = t.maxCount,
            id = t.id,
        )
        // Backend scheme may use any groupKey; map known ones, rest → extra.
        val byGroup = p.toppings.groupBy { it.groupKey.trim().lowercase() }
        val known = setOf("milk", "молоко", "syrup", "сироп", "sugar", "сахар", "topping", "extra", "other", "добавки", "добавка")
        fun toOpts(vararg keys: String) = keys.flatMap { k -> byGroup[k].orEmpty().map { fromTopping(it) } }
        val restExtra = byGroup.filterKeys { it !in known }.values.flatten().map { fromTopping(it) }
        // Merge Evotor #opt= inventory items with backend toppings (do not drop one for the other).
        return OptionPicker.Opts(
            milk = mapEv(evotor.milk) + toOpts("milk", "молоко"),
            syrup = mapEv(evotor.syrup) + toOpts("syrup", "сироп"),
            sugar = mapEv(evotor.sugar) + toOpts("sugar", "сахар"),
            extra = mapEv(evotor.extra, max = EVOTOR_EXTRA_LIMIT) +
                toOpts("topping", "extra", "other", "добавки", "добавка") + restExtra,
        )
    }

    /**
     * item-sheet 1:1 index: hero → название+цена → описание → теги → «в чеке уже N» →
     * опции → qty + «В чек · сумма».
     */
    private fun showItemSheet(p: CatalogProduct) {
        dismissSheets()
        val variants = variantModel.variantsOf(p)
        val hasTops = variants.any { it.toppings.isNotEmpty() } || p.toppings.isNotEmpty()
        val opts = if (p.supportsMods || hasTops || !variantModel.options.isEmpty) optionsFor(
            // Prefer variant that already carries toppings from backend merge.
            variants.firstOrNull { it.toppings.isNotEmpty() } ?: p
        ) else OptionPicker.Opts()
        Log.i("SC_TOP", "sheet base=${p.name} variants=${variants.size} topsPerVariant=${variants.map { it.toppings.size }} opts(milk=${opts.milk.size},syrup=${opts.syrup.size},extra=${opts.extra.size})")

        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WHITE)
        }
        root.addView(buildItemHero(p) { dialog.dismiss() }, LinearLayout.LayoutParams(MATCH, dp(128)))

        val scroll = NestedScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val priceView = TextView(this).apply {
            textSize = 17f
            letterSpacing = -0.02f
            weight(Fonts.BOLD)
            setTextColor(INK)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        titleRow.addView(TextView(this).apply {
            text = p.name
            textSize = 20f
            letterSpacing = -0.025f
            weight(Fonts.STRONG)
            setLineSpacing(0f, 0.95f)
            setTextColor(INK)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(10) })
        titleRow.addView(priceView)
        body.addView(titleRow)

        // Рецепт только по «i» / восклицательному знаку (showRecipe), не в sheet.
        val tags = itemTags(p)
        if (tags.childCount > 0) body.addView(tags)

        val existing = Cart.all()
            .filter { it.productUuid in variants.map { v -> v.uuid } }
            .fold(BigDecimal.ZERO) { acc, l -> acc.add(l.quantity) }
        if (existing.signum() > 0) {
            body.addView(TextView(this).apply {
                text = "В чеке уже $existing порц. Новая сохранится отдельной позицией."
                textSize = 10f
                setTextColor(UiKit.MUTED)
                setPadding(dp(9), dp(7), dp(9), dp(7))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(UiKit.SURFACE_2); cornerRadius = dp(9).toFloat()
                    setStroke(dp(1), UiKit.LINE_STRONG, dp(3).toFloat(), dp(3).toFloat())
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(9) })
        }

        val addBtn = TextView(this).apply {
            textSize = 12.5f
            setOnest(true)
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            background = rounded(BRAND, 14, null)
            isClickable = true
            isFocusable = true
        }
        val qtyLabel = TextView(this).apply {
            text = "1"
            textSize = 12.5f
            weight(Fonts.STRONG)
            gravity = Gravity.CENTER
            setTextColor(INK)
            minWidth = dp(20)
        }
        var qty = 1

        // Платная добавка без товара Эвотор не выбирается: фиксируем в журнале, что схема неполная.
        val blockedToppings = listOf(opts.milk, opts.syrup, opts.sugar, opts.extra).flatten()
            .filter { it.price.signum() > 0 && !it.zeroOption && it.evotorUuid.isNullOrBlank() }
            .mapNotNull { it.id }
        if (blockedToppings.isNotEmpty()) {
            Log.w(TAG, "topping_blocked_no_uuid productId=${productIdOf(p.uuid)} toppingIds=$blockedToppings")
        }

        val picker = OptionPicker(this)
        picker.bind(variants, opts, p)
        body.addView(picker, LinearLayout.LayoutParams(MATCH, WRAP))

        fun paint() {
            priceView.text = rub(picker.unitPrice())
            val blocked = picker.blockers()
            addBtn.text = if (blocked.isEmpty()) "В чек · ${rub(picker.unitPrice().multiply(BigDecimal(qty)))}" else blocked.first()
            addBtn.alpha = if (blocked.isEmpty()) 1f else 0.55f
            addBtn.isEnabled = blocked.isEmpty()
            qtyLabel.text = qty.toString()
        }
        picker.onChange = { paint() }
        // Смена объёма: пересчитать opts для выбранного варианта (rebind), не оставлять
        // opts от первого variant на весь sheet.
        picker.onVariantChanged = { selected ->
            Log.i("SC_TOP", "volumeChanged -> name=${selected.name} tops=${selected.toppings.size}")
            val newOpts = if (selected.supportsMods || selected.toppings.isNotEmpty() || !variantModel.options.isEmpty)
                optionsFor(selected) else OptionPicker.Opts()
            picker.rebindOpts(selected, newOpts)
            paint()
        }
        paint()

        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(11))
            background = topBorder(WHITE)
        }
        val qtyWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(UiKit.SURFACE_2, 13, LINE)
        }
        fun qBtn(label: String, act: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(UiKit.INK_2)
            isClickable = true
            setOnClickListener { act() }
        }
        qtyWrap.addView(qBtn("−") {
            if (qty > 1) { qty -= 1; paint() }
        }, LinearLayout.LayoutParams(dp(44), dp(50)))
        qtyWrap.addView(qtyLabel, LinearLayout.LayoutParams(WRAP, dp(50)))
        qtyWrap.addView(qBtn("+") {
            if (qty < 99) { qty += 1; paint() }
        }, LinearLayout.LayoutParams(dp(44), dp(50)))
        foot.addView(qtyWrap, LinearLayout.LayoutParams(WRAP, dp(50)).apply { rightMargin = dp(10) })
        foot.addView(addBtn, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        addBtn.setOnClickListener {
            val blocked = picker.blockers()
            if (blocked.isNotEmpty()) {
                Toast.makeText(this, blocked.first(), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val variant = picker.selectedVariant ?: return@setOnClickListener
            val chosen = picker.chosen()
            if (chosen.isNotEmpty()) {
                Log.i(
                    TAG,
                    "toppings_selected productId=${productIdOf(variant.uuid)} " +
                        "toppingIds=${chosen.mapNotNull { it.id }} " +
                        "total=${chosen.fold(BigDecimal.ZERO) { acc, o -> acc.add(o.price) }.toPlainString()}",
                )
            }
            val signature = picker.signature().ifBlank { variant.uuid }
            repeat(qty) {
                Cart.add(
                    CartLine(
                        variant.uuid,
                        variant.name,
                        variant.priceRub,
                        BigDecimal.ONE,
                        picker.modsText(),
                        variant.freeEligible,
                        signature,
                    ),
                )
                // Каждая опция с товаром Эвотор — отдельная позиция: склад и отчётность сходятся.
                picker.chosen().forEach { o ->
                    val uuid = o.evotorUuid?.takeIf { it.isNotBlank() } ?: return@forEach
                    Cart.add(CartLine(uuid, "${o.label} (${variant.name})", o.price, BigDecimal.ONE, optionId = o.id))
                }
            }
            onCartChanged()
            dialog.dismiss()
        }

        Fonts.apply(root)
        dialog.setContentView(root)
        styleSheet(dialog)
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    /**
     * combo-sheet 1:1 index: два фото со «+», название и цена набора, выгода,
     * состав (напиток + выпечка), опции напитка, «В заказ · цена».
     *
     * В чек уходят две реальные позиции; выгода — скидкой на чек.
     */
    private fun showComboSheet(combo: Combo, editingGroupId: String? = null) {
        dismissSheets()
        val drink = products.firstOrNull { it.uuid == combo.drinkUuid }
        val food = products.firstOrNull { it.uuid == combo.foodUuid }
        if (drink == null || food == null) {
            notice = "Комбо «${combo.name}»: в каталоге нет состава"
            noticeKind = Kind.ERROR
            safeRender()
            return
        }
        val variants = variantModel.variantsOf(drink)
        val opts = if (drink.supportsMods || drink.toppings.isNotEmpty() || !variantModel.options.isEmpty) optionsFor(drink) else OptionPicker.Opts()

        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WHITE)
        }

        // ── hero: два фото + «+» ──
        val hero = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFF7F2E9.toInt(), 0xFFECE3D6.toInt()),
            )
        }
        val pics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        pics.addView(ThumbView(this).apply { setCorner(0); setContain(104, 86); bind(drink.name, drink.imageUrl, 360) },
            LinearLayout.LayoutParams(dp(104), dp(86)))
        pics.addView(TextView(this).apply {
            text = "+"
            textSize = 13f
            weight(Fonts.MEDIUM)
            setTextColor(UiKit.MUTED)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(16), WRAP).apply { leftMargin = dp(4); rightMargin = dp(4) })
        pics.addView(ThumbView(this).apply { setCorner(0); setContain(104, 86); bind(food.name, food.imageUrl, 360) },
            LinearLayout.LayoutParams(dp(104), dp(86)))
        hero.addView(pics, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        hero.addView(TextView(this).apply {
            text = "КОМБО"
            textSize = 10f
            setOnest(true)
            setTextColor(UiKit.INK_2)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = rounded(0xEBFFFFFF.toInt(), 5, null)
        }, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(12); leftMargin = dp(16)
        })
        hero.addView(closeButton("Закрыть") { dialog.dismiss() }.apply { background = rounded(0xE6FFFFFF.toInt(), 22, null); Ds.shadowS(this) }, FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(10); rightMargin = dp(12)
        })
        root.addView(hero, LinearLayout.LayoutParams(MATCH, dp(128)))

        val scroll = NestedScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val priceView = TextView(this).apply {
            textSize = 17f
            letterSpacing = -0.02f
            weight(Fonts.BOLD)
            setTextColor(INK)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        titleRow.addView(TextView(this).apply {
            text = combo.name
            textSize = 20f
            letterSpacing = -0.025f
            weight(Fonts.STRONG)
            setTextColor(INK)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(10) })
        titleRow.addView(priceView)
        body.addView(titleRow)

        combo.desc?.takeIf { it.isNotBlank() }?.let { d ->
            body.addView(TextView(this).apply {
                text = d
                textSize = 10.5f
                setTextColor(UiKit.MUTED)
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(4), 0, 0)
            })
        }

        // .combo-saving: «Выгода −X ₽ ~~старая~~»
        val oldSum = combo.drinkBasePriceRub.add(combo.foodPriceRub)
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = rounded(UiKit.SUCCESS_SOFT, 6, null)
            addView(TextView(this@MainActivity).apply {
                text = "Выгода"; textSize = 10f; weight(Fonts.MEDIUM); setTextColor(UiKit.SUCCESS)
            })
            addView(TextView(this@MainActivity).apply {
                text = rub(combo.discountRub); textSize = 10f; weight(Fonts.STRONG); setTextColor(UiKit.SUCCESS)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            addView(TextView(this@MainActivity).apply {
                text = rub(oldSum); textSize = 10f; setTextColor(0xFF4A7A5E.toInt()); alpha = 0.8f
                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })

        // .combo-composition
        val comp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(UiKit.SURFACE_2, 11, LINE)
        }
        body.addView(comp, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        val compHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(0, 0, 0, dp(4)) }
        compHeader.addView(TextView(this).apply {
            text = "СОСТАВ КОМБО"; textSize = 10f; letterSpacing = 0.11f; weight(Fonts.STRONG); setTextColor(UiKit.INK_2)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        compHeader.addView(TextView(this).apply { text = "2 позиции в чеке"; textSize = 10f; setTextColor(UiKit.MUTED) })
        comp.addView(compHeader)

        fun compRow(p: CatalogProduct, detail: String?, price: BigDecimal) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(ThumbView(this).apply { setCorner(7); bind(p.name, p.imageUrl, 200) },
                LinearLayout.LayoutParams(dp(32), dp(32)))
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), 0, dp(6), 0)
            }
            col.addView(TextView(this).apply {
                text = p.name
                textSize = 11f
                setOnest(true)
                setTextColor(INK)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(this).apply {
                text = detail.orEmpty()
                textSize = 10f
                setTextColor(UiKit.MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(TextView(this).apply {
                text = rub(price)
                textSize = 11f
                setTextColor(UiKit.INK_2)
            })
            comp.addView(row)
        }

        val foodPrice = combo.foodPriceRub

        val picker = OptionPicker(this)
        // При редактировании подставляем уже выбранный в чеке объём.
        val editingDrink = editingGroupId?.let { gid ->
            val uuids = variants.map { it.uuid }.toSet()
            Cart.comboLines(gid).firstOrNull { it.productUuid in uuids }
        }
        val preselect = editingGroupId?.let { gid ->
            Cart.comboLines(gid).filter { it.cartKey.contains("#opt:") }.map { it.productUuid }.toSet()
        }.orEmpty()
        picker.bind(
            variants,
            opts,
            editingDrink?.let { line -> variants.firstOrNull { it.uuid == line.productUuid } } ?: drink,
            preselect,
        )
        // строка напитка обновляется вместе с опциями
        val drinkRowHost = LinearLayout(this)
        comp.addView(drinkRowHost, LinearLayout.LayoutParams(MATCH, WRAP))
        compRow(food, null, foodPrice)
        fun paintDrinkRow() {
            drinkRowHost.removeAllViews()
            val v = picker.selectedVariant ?: drink
            val detail = picker.modsText().ifBlank { null }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(ThumbView(this).apply { setCorner(7); bind(v.name, v.imageUrl, 200) },
                LinearLayout.LayoutParams(dp(32), dp(32)))
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), 0, dp(6), 0)
            }
            col.addView(TextView(this).apply {
                text = v.name
                textSize = 11f
                setOnest(true)
                setTextColor(INK)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (detail != null) col.addView(TextView(this).apply {
                text = detail
                textSize = 10f
                setTextColor(UiKit.MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(TextView(this).apply {
                text = rub(v.priceRub)
                textSize = 11f
                setTextColor(UiKit.INK_2)
            })
            drinkRowHost.addView(row)
        }
        paintDrinkRow()
        body.addView(TextView(this).apply {
            text = "ОПЦИИ НАПИТКА"; textSize = 10f; letterSpacing = 0.11f; weight(Fonts.STRONG); setTextColor(UiKit.INK_2)
            setPadding(0, dp(16), 0, 0)
        })
        body.addView(TextView(this).apply {
            text = "как у обычного ${drink.name.lowercase()}"; textSize = 10f; setTextColor(UiKit.MUTED); setPadding(0, dp(2), 0, 0)
        })
        body.addView(picker, LinearLayout.LayoutParams(MATCH, WRAP))

        val addBtn = TextView(this).apply {
            textSize = 12.5f
            setOnest(true)
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            background = rounded(BRAND, 14, null)
            isClickable = true
            isFocusable = true
        }
        fun paint() {
            val total = picker.unitPrice().add(foodPrice)
            priceView.text = rub(total.subtract(combo.discountRub))
            val blocked = picker.blockers()
            addBtn.text = if (blocked.isEmpty()) {
                (if (editingGroupId != null) "Сохранить · " else "В заказ · ") + rub(total.subtract(combo.discountRub))
            } else blocked.first()
            addBtn.alpha = if (blocked.isEmpty()) 1f else 0.55f
            addBtn.isEnabled = blocked.isEmpty()
        }
        picker.onChange = { paintDrinkRow(); paint() }
        paint()

        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(11))
            background = topBorder(WHITE)
        }
        foot.addView(addBtn, LinearLayout.LayoutParams(MATCH, dp(50)))
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        addBtn.setOnClickListener {
            val blocked = picker.blockers()
            if (blocked.isNotEmpty()) {
                Toast.makeText(this, blocked.first(), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val variant = picker.selectedVariant ?: drink
            val groupId = editingGroupId ?: ("combo-" + System.nanoTime())
            if (editingGroupId != null) Cart.removeComboGroup(editingGroupId)
            val mods = picker.modsText()
            Cart.add(
                CartLine(
                    productUuid = variant.uuid,
                    name = variant.name,
                    priceRub = variant.priceRub,
                    quantity = BigDecimal.ONE,
                    recipe = mods,
                    freeEligible = variant.freeEligible,
                    cartKey = "$groupId#drink",
                    comboGroupId = groupId,
                    comboId = combo.id,
                ),
            )
            Cart.add(
                CartLine(
                    productUuid = food.uuid,
                    name = food.name,
                    priceRub = food.priceRub,
                    quantity = BigDecimal.ONE,
                    recipe = null,
                    freeEligible = food.freeEligible,
                    cartKey = "$groupId#food",
                    comboGroupId = groupId,
                    comboId = combo.id,
                ),
            )
            picker.chosen().forEach { o ->
                val uuid = o.evotorUuid?.takeIf { it.isNotBlank() } ?: return@forEach
                Cart.add(
                    CartLine(
                        productUuid = uuid,
                        name = "${o.label} (${variant.name})",
                        priceRub = o.price,
                        quantity = BigDecimal.ONE,
                        cartKey = "$groupId#opt:$uuid",
                        comboGroupId = groupId,
                        comboId = combo.id,
                        optionId = o.id,
                    ),
                )
            }
            onCartChanged()
            dialog.dismiss()
        }

        Fonts.apply(root)
        dialog.setContentView(root)
        styleSheet(dialog)
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    /** hero item-sheet: фото, флаг «Хит/Комбо», крестик. */
    private fun buildItemHero(p: CatalogProduct, onClose: () -> Unit): View {
        val hero = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFF7F2E9.toInt(), 0xFFECE3D6.toInt()),
            )
        }
        hero.addView(ThumbView(this).apply { setCorner(0); setContain(150, 120); bind(p.name, p.imageUrl, 480) }, FrameLayout.LayoutParams(MATCH, MATCH))
        val badge = when {
            p.isCombo -> "КОМБО"
            p.featured -> "ХИТ"
            else -> null
        }
        if (badge != null) {
            hero.addView(TextView(this).apply {
                text = badge
                textSize = 10f
                letterSpacing = 0.09f
                weight(Fonts.STRONG)
                setTextColor(UiKit.INK_2)
                setPadding(dp(6), dp(3), dp(6), dp(3))
                background = rounded(0xEBFFFFFF.toInt(), 5, null)
            }, FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(12); leftMargin = dp(16)
            })
        }
        hero.addView(closeButton("Закрыть", onClose).apply {
            background = rounded(0xE6FFFFFF.toInt(), 22, null)
            Ds.shadowS(this)
        }, FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(10); rightMargin = dp(12)
        })
        return hero
    }

    /** Теги позиции: группа Эвотор + метка «6.7» для напитков. */
    private fun itemTags(p: CatalogProduct): LinearLayout {
        val tags = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, 0)
        }
        listOfNotNull(
            p.groupName?.takeIf { it.isNotBlank() },
            if (p.freeEligible) "6.7" else null,
        ).forEach { t ->
            tags.addView(TextView(this).apply {
                text = t.uppercase()
                textSize = 10f
                letterSpacing = 0.07f
                weight(Fonts.SEMIBOLD)
                setTextColor(UiKit.MUTED)
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = rounded(WHITE, 4, LINE)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(5) })
        }
        return tags
    }

    /** Скидка комбо по текущей корзине (рубли). */
    private fun comboDiscountRub(): BigDecimal = Combos.discountFor(Cart.all(), combos)

    /** Итог к оплате: сумма реальных позиций минус выгода комбо. */
    private fun cartTotalRub(): BigDecimal = Cart.gross().subtract(comboDiscountRub()).max(BigDecimal.ZERO)

    /** Корзина изменилась: обновить плашки «×N» (payload, без перебинда) и нижнюю полосу. */
    private fun onCartChanged() {
        if (Cart.isEmpty()) recShown = false
        adapter?.refreshQty()
        updateBar()
        // Скидку комбо читает DiscountIntegrationService при открытии чека.
        CheckoutChoice.saveCombo(this, comboDiscountRub())
    }

    private fun updateBar() {
        val empty = Cart.isEmpty()
        val n = cartLineCount(Cart.all())
        cartCountView?.text = n.toString()
        cartTotalView?.text = rub(cartTotalRub())
        cartSubView?.text = "Чек · ${orderType.lowercase()}"
        cartBarRoot?.visibility = if (empty) View.GONE else View.VISIBLE
        emptyCartBarRoot?.visibility = if (empty) View.VISIBLE else View.GONE
        repeatOrderView?.visibility = if (Cart.hasLastOrder()) View.VISIBLE else View.GONE
        val cta = cartBarRoot?.tag as? View
        cta?.isEnabled = !empty
        cta?.alpha = if (empty) 0.5f else 1f
        refreshCartSidebar()
    }

    /**
     * cartbar index: dark bar height 66, count circle, sum, CTA brand «В чек».
     * Тап по count/sum → showCart; CTA «В чек» → showUpsellThenReceipt.
     * empty-cartbar: без «Повторить» — прошлого заказа в APK нет (не выдумываем).
     */
    private fun buildCartBar(card: CardInfo?): View {
        val m = Ds.metrics(this)
        val wrap = FrameLayout(this)

        // .empty-cartbar: пунктир, высота 62, «Повторить» 36×радиус 10
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF2FBF9F5.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), UiKit.LINE_STRONG, dp(4).toFloat(), dp(3).toFloat())
            }
        }
        val emptyText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emptyText.addView(TextView(this).apply {
            text = "Чек пуст"
            textSize = 12f
            weight(Fonts.SEMIBOLD)
            setTextColor(INK)
        })
        emptyText.addView(TextView(this).apply {
            text = if (Cart.hasLastOrder()) "Начните с хитов или повторите прошлый" else "Начните с хитов"
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(1), 0, 0)
        })
        empty.addView(emptyText, LinearLayout.LayoutParams(0, WRAP, 1f))
        val repeat = TextView(this).apply {
            text = "Повторить"
            textSize = 10.5f
            weight(Fonts.SEMIBOLD)
            setTextColor(INK)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(WHITE, 10, LINE)
            isClickable = true
            contentDescription = "Повторить прошлый заказ"
            visibility = if (Cart.hasLastOrder()) View.VISIBLE else View.GONE
            setOnClickListener {
                if (Cart.repeatLast()) {
                    onCartChanged()
                } else {
                    notice = "Повторить нечего — прошлый заказ не сохранён"
                    noticeKind = Kind.WARN
                    safeRender()
                }
            }
        }
        repeatOrderView = repeat
        empty.addView(repeat, LinearLayout.LayoutParams(WRAP, dp(36)).apply { leftMargin = dp(10) })
        emptyCartBarRoot = empty
        wrap.addView(empty, FrameLayout.LayoutParams(MATCH, dp(62)))

        // .cartbar: тёмная плашка 66/70, счётчик 31, сумма, CTA
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(8), 0)
            background = rounded(0xFF171410.toInt(), 20, null)
            elevation = 10f * resources.displayMetrics.density
        }
        val count = TextView(this).apply {
            text = "0"
            textSize = 12f
            weight(Fonts.STRONG)
            gravity = Gravity.CENTER
            setTextColor(0xFF171410.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFF6F3EE.toInt()) }
        }
        cartCountView = count
        bar.addView(count, LinearLayout.LayoutParams(dp(31), dp(31)))

        val sumCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { showCart() }
        }
        val sub = TextView(this).apply {
            text = "Чек · ${orderType.lowercase()}"
            textSize = 10f
            letterSpacing = 0.04f
            weight(Fonts.MEDIUM)
            setTextColor(0x9EF6F3EE.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        cartSubView = sub
        sumCol.addView(sub)
        val total = TextView(this).apply {
            text = "0 ₽"
            textSize = m.sumSp
            letterSpacing = -0.02f
            weight(Fonts.STRONG)
            setTextColor(WHITE)
            maxLines = 1
            includeFontPadding = false
        }
        cartTotalView = total
        sumCol.addView(total, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        bar.addView(sumCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        val cta = TextView(this).apply {
            text = "В чек"
            textSize = 12f
            letterSpacing = 0.01f
            weight(Fonts.SEMIBOLD)
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            compoundDrawablePadding = dp(7)
            setCompoundDrawables(null, null, tinted(R.drawable.ic_arrow_right, WHITE, 15), null)
            setPadding(dp(if (m.mode == Ds.Mode.M5) 14 else 18), 0, dp(if (m.mode == Ds.Mode.M5) 14 else 18), 0)
            background = rounded(BRAND, 14, null)
            isClickable = true
            isFocusable = true
            contentDescription = "Передать позиции в чек Эвотор"
            setOnClickListener { showUpsellThenReceipt(card) }
        }
        bar.addView(cta, LinearLayout.LayoutParams(WRAP, dp(m.ctaH)))
        bar.tag = cta
        cartBarRoot = bar
        wrap.addView(bar, FrameLayout.LayoutParams(MATCH, dp(m.barH)))
        return wrap
    }

    private fun buildPosHead(card: CardInfo?): View {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // lockup
        val lockup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.logo_67_tile)
            contentDescription = "6.7 Coffee"
        }
        lockup.addView(logo, LinearLayout.LayoutParams(dp(34), dp(34)))

        val brandCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), 0, 0, 0)
        }
        brandCol.addView(TextView(this).apply {
            text = "6.7 Coffee"
            textSize = 14.5f
            setTextColor(INK)
            letterSpacing = -0.015f
            weight(Fonts.STRONG)
            includeFontPadding = false
            maxLines = 1
        })
        // shift-chip visual (index) — действия = существующий showMore
        val shift = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Меню смены"
        }
        // .shift-dot: 5px + кольцо 2px success-soft
        val dot = View(this).apply {
            val chipOn = shiftOpenState()
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (chipOn == true) UiKit.SUCCESS_SOFT else UiKit.LINE) },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(when (chipOn) { true -> UiKit.SUCCESS; false -> UiKit.DANGER; null -> UiKit.MUTED }) },
            )).apply { setLayerInset(1, dp(2), dp(2), dp(2), dp(2)) }
        }
        shift.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(2); gravity = Gravity.CENTER_VERTICAL })
        shift.addView(TextView(this).apply {
            text = when (shiftOpenState()) { true -> "Смена открыта"; false -> "Смена не открыта"; null -> "Смена" }
            textSize = 10f
            weight(Fonts.MEDIUM)
            setTextColor(UiKit.MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        shift.addView(icon(this, R.drawable.ic_chevron_sm, 9f, UiKit.MUTED).apply { alpha = 0.75f },
            LinearLayout.LayoutParams(dp(9), dp(9)).apply { leftMargin = dp(4) })
        shift.setOnClickListener {
            shiftView = "home"
            notice = null
            screen = Screen.SHIFT
            safeRender()
        }
        brandCol.addView(shift, LinearLayout.LayoutParams(WRAP, WRAP))
        lockup.addView(brandCol, LinearLayout.LayoutParams(WRAP, WRAP))

        head.addView(lockup, LinearLayout.LayoutParams(0, WRAP, 1f))
        queueIndicator()?.let { head.addView(it, LinearLayout.LayoutParams(WRAP, dp(48)).apply { leftMargin = dp(8) }) }
        head.addView(guestChip(card, clickable = true), LinearLayout.LayoutParams(WRAP, dp(48)).apply { leftMargin = dp(10) })
        return head
    }

    /** guest-chip 1:1 с index: avatar + name + cups. Тап → экран карты (как раньше). */
    private fun guestChip(card: CardInfo?, clickable: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(10), 0)
            background = rounded(WHITE, 999, LINE)
            minimumHeight = dp(48)
            Ds.shadowS(this)
        }
        val letter: String
        val name: String
        val cups: String
        val empty = card == null || card.code.isEmpty()
        if (empty) {
            letter = "?"
            name = "Гость"
            cups = "Карта не привязана"
        } else {
            val code = card!!.cardCode.ifBlank { "Карта" }
            letter = code.first().uppercaseChar().toString()
            name = code
            cups = when {
                card.freeStatus == "OFFLINE" -> "Нет связи · бонусы недоступны"
                else -> {
                    val p = CardProgress.of(card)
                    "${p.cups} из ${p.goal} чашек"
                }
            }
        }
        val avatar = AvatarView(this).apply {
            bind(letter, solid = !empty, textSp = 12.5f)
        }
        box.addView(avatar, LinearLayout.LayoutParams(dp(33), dp(33)))
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        meta.addView(TextView(this).apply {
            text = name
            textSize = 11.5f
            weight(Fonts.SEMIBOLD)
            setTextColor(INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        meta.addView(TextView(this).apply {
            text = cups
            textSize = 10f
            weight(Fonts.MEDIUM)
            setTextColor(UiKit.MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        box.addView(meta, LinearLayout.LayoutParams(WRAP, WRAP))
        if (clickable) {
            box.isClickable = true
            box.isFocusable = true
            box.contentDescription = "Карта гостя"
            box.setOnClickListener { notice = null; screen = Screen.CARD; safeRender() }
        }
        return box
    }

    /** Совместимость: старые вызовы cardChip → guestChip. */
    private fun cardChip(card: CardInfo?, clickable: Boolean): View = guestChip(card, clickable)

    private fun showRecipe(p: CatalogProduct) {
        fun showText(r: String?) {
            if (isFinishing || isDestroyed) return
            AlertDialog.Builder(this).setTitle(p.name).setMessage(r ?: "Рецепт для этой позиции не указан.").setPositiveButton("Закрыть", null).show()
        }
        // recipeText с backend (после merge лежит в p.recipe) или DESCRIPTION кассы по «i».
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

    /**
     * cart-sheet 1:1 index: head (Чек + count + Очистить + close),
     * order-type, cart-list rows, note, foot (Итого + «В чек»).
     * Upsell track — из upsellDefault, только товары с evotorUuid в каталоге.
     * orderType — только подпись UI, в Эвотор не уходит.
     */
    /** Строк в чеке: комбо-группа считается одной позицией (как `cartLineCount` в index). */
    private fun cartLineCount(lines: List<CartLine>): Int =
        lines.count { it.comboGroupId == null } + lines.mapNotNull { it.comboGroupId }.distinct().size

    /** Карточка комбо в чеке: название набора, цена со скидкой, состав, «Изменить»/удалить. */
    private fun buildComboCartCard(groupId: String): View {
        val rows = Cart.comboLines(groupId)
        val combo = rows.firstOrNull()?.comboId?.let { id -> combos.firstOrNull { it.id == id } }
        val componentSum = rows.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.lineTotal()) }
        val price = combo?.let { componentSum.subtract(it.discountRub) } ?: componentSum

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(UiKit.SURFACE_2, 14, LINE)
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        badgeRow.addView(TextView(this).apply {
            text = "КОМБО"
            textSize = 10f
            setOnest(true)
            setTextColor(UiKit.WHITE)
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background = rounded(BRAND, 4, null)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        badgeRow.addView(TextView(this).apply {
            text = combo?.name ?: "Комбо"
            textSize = 12f
            setOnest(true)
            setTextColor(INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        titleCol.addView(badgeRow)

        if (combo != null && combo.discountRub.signum() > 0) {
            titleCol.addView(TextView(this).apply {
                text = "выгода ${rub(combo.discountRub)} · ${rub(componentSum)}"
                textSize = 10f
                setTextColor(UiKit.MUTED)
                setPadding(0, dp(2), 0, 0)
            })
        }
        head.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(TextView(this).apply {
            text = rub(price)
            textSize = 12.5f
            setOnest(true)
            setTextColor(INK)
        })
        head.addView(closeButton("Удалить комбо") {
                Cart.removeComboGroup(groupId)
                onCartChanged()
                showCart()
            }.apply { background = null }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(4) })
        card.addView(head)

        rows.filterNot { it.cartKey.contains("#opt:") }.forEach { l ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = l.name
                textSize = 10.5f
                setTextColor(UiKit.INK_2)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (!l.recipe.isNullOrBlank()) col.addView(TextView(this).apply {
                text = l.recipe
                textSize = 10f
                setTextColor(UiKit.MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(TextView(this).apply {
                text = rub(l.lineTotal())
                textSize = 10f
                setTextColor(UiKit.MUTED)
            })
            card.addView(row)
        }

        if (combo != null) {
            card.addView(TextView(this).apply {
                text = "Изменить опции"
                textSize = 10f
                setOnest(true)
                setTextColor(BRAND)
                setPadding(0, dp(8), 0, 0)
                isClickable = true
                setOnClickListener {
                    showComboSheet(combo, groupId)
                }
            })
        }
        return card
    }
    /**
     * Лояльность в чеке (index `.loyalty-card`): тёмная карта с чашками и бонусами.
     * Без карты — пунктирная плашка «Привязать карту гостя».
     * Список бонусов/подарка решается в листе «Лояльность» перед «В чек».
     */
    private fun buildCartLoyalty(card: CardInfo?): View {
        if (card == null || card.code.isEmpty()) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded(UiKit.SURFACE_2, 14, UiKit.LINE_STRONG)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismissSheets()
                    screen = Screen.CARD
                    safeRender()
                }
            }
            box.addView(TextView(this).apply {
                text = "+"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(BRAND)
                background = rounded(WHITE, 15, LINE)
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(9) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = "Привязать карту гостя"
                textSize = 11f
                setOnest(true)
                setTextColor(INK)
            })
            col.addView(TextView(this).apply {
                text = "Чашки и бонусы спишутся в этом чеке"
                textSize = 10f
                setTextColor(UiKit.MUTED)
            })
            box.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            box.addView(TextView(this).apply {
                text = "Сканировать"
                textSize = 10f
                setOnest(true)
                setTextColor(UiKit.INK_2)
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                background = rounded(WHITE, 8, LINE)
            }, LinearLayout.LayoutParams(WRAP, dp(32)))
            return box
        }

        val progress = CardProgress.of(card)
        val cups = progress.cups
        val goal = progress.goal

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(12))
            background = rounded(UiKit.INK, 16, null)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val code = card.cardCode.ifBlank { "Карта" }
        top.addView(AvatarView(this).apply { bind(code, solid = true, textSp = 10f) },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(7) })
        top.addView(TextView(this).apply {
            text = code
            textSize = 11.5f
            setOnest(true)
            setTextColor(0xFFF6F3EE.toInt())
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (card.cashbackKopecks > 0) {
            top.addView(TextView(this).apply {
                text = "${card.cashbackKopecks / 100} бонусов"
                textSize = 10f
                setTextColor(0xB3F6F3EE.toInt())
            })
        }
        top.addView(TextView(this).apply {
            text = if (CardSession.isStale(this@MainActivity)) "Обновить ·" else "Обновить"
            textSize = 10f
            setOnest(true)
            setTextColor(0x99F6F3EE.toInt())
            isClickable = true
            isFocusable = true
            contentDescription = "Обновить данные карты"
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { refreshCardAsync(notifyOnChange = true) }
        })
        box.addView(top)

        box.addView(TextView(this).apply {
            text = if (progress.giftAvailable) "Подарок доступен" else "$cups из $goal чашек"
            textSize = 10.5f
            setOnest(true)
            setTextColor(0xFFF6F3EE.toInt())
            setPadding(0, dp(9), 0, 0)
        })
        box.addView(TextView(this).apply {
            text = loyaltyHint(card, progress) ?: when {
                card.freeStatus == "OFFLINE" -> "Нет связи · бонусы недоступны"
                progress.redeemEnabled -> "1 бесплатный напиток"
                !progress.giftKnown -> "Бонусы и подарок — по QR-карте"
                else -> "${progress.left} до подарка"
            }
            textSize = 10f
            setTextColor(0xB3F6F3EE.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        box.addView(
            UiKit.loyaltyDots(this, cups, goal, onColor = 0xFFF6F3EE.toInt()),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) },
        )
        return box
    }

    /**
     * Панель чека `.cart-sheet`. docked = true — закреплена справа на 10″ (без ручки и «✕»), иначе содержимое bottom-sheet.
     * Возвращает корневую view и функцию перерисовки.
     */
    private fun buildCartPanel(docked: Boolean, close: () -> Unit): Pair<View, () -> Unit> {
        val sidePad = if (docked) 18 else 16
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WHITE)
        }
        // sheet-grip 34×4
        if (!docked) root.addView(View(this).apply {
            background = rounded(UiKit.LINE_STRONG, 2, null)
        }, LinearLayout.LayoutParams(dp(34), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(7); bottomMargin = dp(0)
        })

        // sheet-head
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(sidePad), dp(if (docked) 18 else 10), dp(if (docked) sidePad else 16), dp(8))
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = "Чек"
            textSize = 16f
            letterSpacing = -0.02f
            weight(Fonts.STRONG)
            setTextColor(INK)
        }
        val countBadge = TextView(this).apply {
            textSize = 10f
            setOnest(true)
            setTextColor(UiKit.MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(5), 0, dp(5), 0)
            background = rounded(UiKit.SURFACE_2, 5, LINE)
            minWidth = dp(20)
            minHeight = dp(19)
        }
        titleRow.addView(titleTv)
        titleRow.addView(countBadge, LinearLayout.LayoutParams(WRAP, dp(19)).apply { leftMargin = dp(6) })
        titleCol.addView(titleRow)
        val subTv = TextView(this).apply {
            textSize = 10f
            setTextColor(UiKit.MUTED)
        }
        titleCol.addView(subTv)
        head.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        val clearBtn = TextView(this).apply {
            text = "Очистить"
            textSize = 12f
            setTextColor(UiKit.MUTED)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            contentDescription = "Очистить заказ"
        }
        head.addView(clearBtn)
        if (!docked) head.addView(closeButton("Закрыть чек", close), LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) })
        root.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroll = NestedScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(sidePad), 0, dp(sidePad), dp(16))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // sheet-foot
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (docked) 18 else 14), dp(10), dp(if (docked) 18 else 14), dp(11))
            background = topBorder(WHITE)
        }
        val footPriceCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        footPriceCol.addView(TextView(this).apply {
            text = "ИТОГО"
            textSize = 10f
            letterSpacing = 0.07f
            setTextColor(UiKit.MUTED)
        })
        val footTotal = TextView(this).apply {
            textSize = 17f
            setOnest(true)
            setTextColor(INK)
        }
        footPriceCol.addView(footTotal)
        foot.addView(footPriceCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        val goPay = TextView(this).apply {
            text = "В чек"
            textSize = 12.5f
            setOnest(true)
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            compoundDrawablePadding = dp(8)
            setCompoundDrawables(null, null, tinted(R.drawable.ic_arrow_right, WHITE, 15), null)
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(BRAND, 14, null)
            isClickable = true
            isFocusable = true
        }
        foot.addView(goPay, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(10) })
        footPriceCol.minimumWidth = dp(84)
        (footPriceCol.layoutParams as LinearLayout.LayoutParams).apply { width = WRAP; weight = 0f }
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        fun paintOrderType(row: LinearLayout) {
            row.removeAllViews()
            listOf("С собой", "Здесь").forEach { label ->
                val on = orderType == label
                val b = TextView(this).apply {
                    text = label
                    textSize = 11f
                    setOnest(true)
                    gravity = Gravity.CENTER
                    setTextColor(if (on) INK else UiKit.MUTED)
                    background = if (on) rounded(WHITE, 9, null) else null
                    isClickable = true
                    setOnClickListener {
                        orderType = label
                        paintOrderType(row)
                        subTv.text = "Чек · ${orderType.lowercase()}"
                    }
                }
                row.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f))
            }
        }

        fun fill() {
            body.removeAllViews()
            body.post { Fonts.apply(body) }
            val lines = Cart.all()
            val n = cartLineCount(lines)
            countBadge.text = n.toString()
            countBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
            subTv.text = "Чек · ${orderType.lowercase()}"
            clearBtn.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            foot.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            footTotal.text = rub(cartTotalRub())
            goPay.setOnClickListener {
                close()
                val card = CardSession.get(this)?.takeIf { it.code.isNotEmpty() }
                showUpsellThenReceipt(card)
            }
            clearBtn.setOnClickListener {
                confirm(
                    "Очистить заказ?",
                    "Позиции сбросятся. Карта гостя останется привязанной.",
                    "Очистить",
                ) {
                    Cart.clear()
                    onCartChanged()
                    fill()
                }
            }

            // order-type
            val typeWrap = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(3), dp(3), dp(3), dp(3))
                background = rounded(UiKit.SURFACE_2, 12, LINE)
            }
            paintOrderType(typeWrap)
            body.addView(typeWrap, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(2); bottomMargin = dp(6)
            })

            if (lines.isEmpty()) {
                body.addView(TextView(this).apply {
                    text = "Чек пуст"
                    textSize = 14f
                    setOnest(true)
                    setTextColor(INK)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(4))
                })
                body.addView(TextView(this).apply {
                    text = "Добавьте позиции из меню"
                    textSize = 12f
                    setTextColor(UiKit.MUTED)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(24))
                })
                return
            }

            val renderedCombos = HashSet<String>()
            lines.forEach { line ->
                val gid = line.comboGroupId
                if (gid != null) {
                    if (renderedCombos.add(gid)) body.addView(buildComboCartCard(gid))
                    return@forEach
                }
                val key = line.cartKey.ifBlank { line.productUuid }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(12), 0, dp(12))
                }
                // .cart-thumb 46×46, радиус 12, градиент; картинка вписана
                val thumb = FrameLayout(this).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFF3EEE5.toInt(), 0xFFECE3D6.toInt()))
                        .apply { cornerRadius = dp(12).toFloat() }
                    clipToOutline = true
                }
                val tv = ThumbView(this).apply { setCorner(0); setContain(48, 39) }
                tv.bind(line.name, products.firstOrNull { it.uuid == line.productUuid }?.imageUrl, 120)
                thumb.addView(tv, FrameLayout.LayoutParams(MATCH, MATCH))
                row.addView(thumb, LinearLayout.LayoutParams(dp(46), dp(46)))

                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, dp(4), 0)
                }
                info.addView(TextView(this).apply {
                    text = line.name
                    textSize = 12.5f
                    setOnest(true)
                    setTextColor(INK)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (!line.recipe.isNullOrBlank()) {
                    info.addView(TextView(this).apply {
                        text = line.recipe
                        textSize = 10.5f
                        setTextColor(UiKit.MUTED)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(3), 0, 0)
                    })
                }
                val lineRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(7), 0, 0)
                }
                lineRow.addView(TextView(this).apply {
                    text = rub(line.lineTotal())
                    textSize = 12.5f
                    setOnest(true)
                    setTextColor(INK)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                val stepper = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(WHITE, 10, LINE)
                }
                fun step(label: String, desc: String, act: () -> Unit) = TextView(this).apply {
                    text = label
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(UiKit.INK_2)
                    contentDescription = "$desc: ${line.name}"
                    isClickable = true
                    setOnClickListener { act(); onCartChanged(); fill() }
                }
                stepper.addView(step("−", "Уменьшить") {
                    Cart.setQty(key, line.quantity.subtract(BigDecimal.ONE))
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                stepper.addView(TextView(this).apply {
                    text = line.quantity.stripTrailingZeros().toPlainString()
                    textSize = 12f
                    setOnest(true)
                    gravity = Gravity.CENTER
                    setTextColor(INK)
                    minWidth = dp(28)
                }, LinearLayout.LayoutParams(WRAP, dp(44)))
                stepper.addView(step("+", "Увеличить") {
                    Cart.add(line.copy(quantity = BigDecimal.ONE))
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                lineRow.addView(stepper, LinearLayout.LayoutParams(WRAP, dp(44)))
                info.addView(lineRow, LinearLayout.LayoutParams(MATCH, WRAP))
                row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))

                val remove = FrameLayout(this).apply {
                    contentDescription = "Удалить: ${line.name}"
                    isClickable = true
                    addView(icon(this@MainActivity, R.drawable.ic_close, 14f, UiKit.MUTED), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                    setOnClickListener {
                        Cart.remove(key)
                        onCartChanged()
                        fill()
                    }
                }
                row.addView(remove, LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(1) })
                body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
                body.addView(View(this).apply { setBackgroundColor(0xFFF1EBE1.toInt()) }, LinearLayout.LayoutParams(MATCH, dp(1)))
            }

            // upsell from backend default — never scheme modifiers (syrup/milk products)
            val modUuids = products.asSequence().flatMap { it.toppings.asSequence() }
                .mapNotNull { it.evotorUuid?.takeIf { u -> u.isNotBlank() } }.toSet()
            val modNames = products.asSequence().flatMap { it.toppings.asSequence().filter { t -> !t.zeroOption } }
                .map { Names.normalize(it.name) }.filter { it.isNotEmpty() }.toSet()
            val upsell = upsellDefault.filter { u ->
                val uuid = u.evotorUuid?.takeIf { it.isNotBlank() }
                    ?: products.firstOrNull { it.name.equals(u.name, true) }?.uuid
                if (uuid == null || Cart.qty(uuid) != 0) return@filter false
                if (uuid in modUuids) return@filter false
                if (Names.normalize(u.name) in modNames) return@filter false
                val prod = products.firstOrNull { it.uuid == uuid }
                if (prod?.hideFromMenu == true) return@filter false
                true
            }.take(6)
            if (upsell.isNotEmpty()) {
                body.addView(TextView(this).apply {
                    text = "ДОБАВИТЬ К ЗАКАЗУ"
                    textSize = 10f
                    setOnest(true)
                    setTextColor(UiKit.MUTED)
                    letterSpacing = 0.12f
                    setPadding(0, dp(14), 0, dp(6))
                })
                val track = HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                }
                val trackRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                upsell.forEach { u ->
                    val uuid = u.evotorUuid?.takeIf { it.isNotBlank() }
                        ?: products.firstOrNull { it.name.equals(u.name, true) }?.uuid
                        ?: return@forEach
                    val chip = TextView(this).apply {
                        text = "${u.name} · ${rub(u.priceRub)}"
                        textSize = 11f
                        setTextColor(INK)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = rounded(WHITE, 12, LINE)
                        isClickable = true
                        setOnClickListener {
                            Cart.add(CartLine(uuid, u.name, u.priceRub, BigDecimal.ONE, null, false))
                            onCartChanged()
                            fill()
                        }
                    }
                    trackRow.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
                }
                track.addView(trackRow)
                body.addView(track, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
            }

            body.addView(buildCartLoyalty(CardSession.get(this)), LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(14)
            })

            // note from index
            body.addView(TextView(this).apply {
                text = "Позиции уйдут в фискальный чек Эвотора. Оплата — на штатном экране терминала."
                textSize = 10.5f
                setTextColor(UiKit.MUTED)
                setPadding(0, dp(10), 0, dp(4))
            })
        }

        fill()
        return root to { fill(); Fonts.apply(body) }
    }

    private fun showCart() {
        if (Ds.mode(this) == Ds.Mode.M10) { cartDockFill?.invoke(); return }
        dismissSheets()
        val dialog = BottomSheetDialog(this)
        val (root, _) = buildCartPanel(false) { dialog.dismiss() }
        Fonts.apply(root)
        Fonts.apply(root)
        dialog.setContentView(root)
        styleSheet(dialog)
        dialog.setOnDismissListener { onCartChanged(); if (cartDialog === dialog) cartDialog = null }
        cartDialog = dialog
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    // ── Лояльность и передача позиций в Эвотор ──


    /** Есть ли в чеке еда — тогда рекомендации не нужны (index: !hasFood()). */
    private fun hasFoodInCart(): Boolean =
        FoodRules.cartHasFood(Cart.all(), products.filter { it.isFood }.map { it.uuid }.toSet())

    private fun showUpsellThenReceipt(card: CardInfo?) {
        val hasFood = hasFoodInCart()
        if (recShown || hasFood) {
            Log.i(TAG, "hasFood=$hasFood upsellShown=false (recShown=$recShown)")
            openLoyaltyOrSend(card)
            return
        }
        Log.i(TAG, "hasFood=false upsellShown=true")
        val inCart = Cart.all().map { it.productUuid }.toSet()
        val modUuidsRec = products.asSequence().flatMap { it.toppings.asSequence() }
            .mapNotNull { it.evotorUuid?.takeIf { x -> x.isNotBlank() } }.toSet()
        val modNamesRec = products.asSequence().flatMap { it.toppings.asSequence().filter { t -> !t.zeroOption } }
            .map { Names.normalize(it.name) }.filter { it.isNotEmpty() }.toSet()
        val candidates = LinkedHashMap<String, UpsellItem>()
        fun resolveUuid(u: UpsellItem): String? {
            u.evotorUuid?.takeIf { it.isNotBlank() }?.let { return it }
            val pid = u.productId
            if (pid <= 0) return null
            lastEnrich?.byUuid?.forEach { (uuid, e) -> if (e.productId == pid) return uuid }
            products.firstOrNull { it.name.equals(u.name, ignoreCase = true) }?.uuid?.let { return it }
            return null
        }
        products.forEach { p ->
            if (p.uuid in inCart) p.upsell.forEach { u ->
                val id = resolveUuid(u) ?: return@forEach
                if (id in modUuidsRec) return@forEach
                if (Names.normalize(u.name) in modNamesRec) return@forEach
                if (products.firstOrNull { it.uuid == id }?.hideFromMenu == true) return@forEach
                candidates[id] = u.copy(evotorUuid = id)
            }
        }
        upsellDefault.forEach { u ->
            val id = resolveUuid(u) ?: return@forEach
            if (id in modUuidsRec) return@forEach
            if (Names.normalize(u.name) in modNamesRec) return@forEach
            if (products.firstOrNull { it.uuid == id }?.hideFromMenu == true) return@forEach
            if (!candidates.containsKey(id)) candidates[id] = u.copy(evotorUuid = id)
        }
        val list = candidates.entries.take(3)
        if (list.isEmpty()) {
            openLoyaltyOrSend(card)
            return
        }
        recShown = true

        // rec-sheet 1:1 index
        dismissSheets()
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WHITE)
        }
        sheetHead(root, "Что-то к напитку?", "Выпечка к кофе · можно пропустить") { dialog.dismiss(); openLoyaltyOrSend(card) }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        body.addView(TextView(this).apply {
            text = "Короткая подборка. Добавьте 0, 1 или несколько — или сразу к оплате."
            textSize = 10f
            setLineSpacing(0f, 1.3f)
            setTextColor(UiKit.MUTED)
            setPadding(0, 0, 0, dp(9))
        })

        val payCta = btn("", "primary") { dialog.dismiss(); openLoyaltyOrSend(card) }.apply { textSize = 11.5f }
        fun paintPay() { payCta.text = "В чек · ${rub(cartTotalRub())}" }
        paintPay()

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listBox)
        fun paintList() {
            listBox.removeAllViews()
            list.forEachIndexed { idx, (uuid, u) ->
                val prod = products.firstOrNull { it.uuid == uuid }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                }
                val thumb = FrameLayout(this).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFF3EEE5.toInt(), 0xFFECE3D6.toInt())).apply { cornerRadius = dp(12).toFloat() }
                    clipToOutline = true
                    addView(ThumbView(this@MainActivity).apply { setCorner(0); setContain(56, 46); bind(u.name, u.imageUrl ?: prod?.imageUrl, 160) },
                        FrameLayout.LayoutParams(MATCH, MATCH))
                }
                row.addView(thumb, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(10) })
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(TextView(this).apply {
                    text = u.name; textSize = 12.5f; weight(Fonts.SEMIBOLD); letterSpacing = -0.005f; setTextColor(INK)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                prod?.recipe?.takeIf { it.isNotBlank() }?.let { meta ->
                    info.addView(TextView(this).apply {
                        text = meta; textSize = 10f; setTextColor(UiKit.MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(1), 0, 0)
                    })
                }
                info.addView(TextView(this).apply {
                    text = rub(u.priceRub); textSize = 12.5f; weight(Fonts.STRONG); setTextColor(INK); setPadding(0, dp(3), 0, 0)
                })
                row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
                val q = Cart.qty(uuid)
                fun add() { Cart.add(CartLine(uuid, u.name, u.priceRub, BigDecimal.ONE, null, false)); onCartChanged(); paintPay(); paintList() }
                if (q == 0) {
                    row.addView(TextView(this).apply {
                        text = "Добавить"; textSize = 10f; weight(Fonts.SEMIBOLD); setTextColor(INK); gravity = Gravity.CENTER
                        setPadding(dp(12), 0, dp(12), 0); background = rounded(WHITE, 9, LINE); isClickable = true
                        setOnClickListener { add() }
                    }, LinearLayout.LayoutParams(WRAP, dp(40)))
                } else {
                    val st = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rounded(WHITE, 9, LINE) }
                    fun stepBtn(label: String, act: () -> Unit) = TextView(this).apply {
                        text = label; textSize = 15f; gravity = Gravity.CENTER; setTextColor(UiKit.INK_2); isClickable = true
                        setOnClickListener { act() }
                    }
                    st.addView(stepBtn("−") { Cart.decProduct(uuid); onCartChanged(); paintPay(); paintList() }, LinearLayout.LayoutParams(dp(36), dp(40)))
                    st.addView(TextView(this).apply { text = q.toString(); textSize = 11f; weight(Fonts.STRONG); gravity = Gravity.CENTER; setTextColor(INK); minWidth = dp(18) }, LinearLayout.LayoutParams(WRAP, dp(40)))
                    st.addView(stepBtn("+") { add() }, LinearLayout.LayoutParams(dp(36), dp(40)))
                    row.addView(st, LinearLayout.LayoutParams(WRAP, dp(40)))
                }
                listBox.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
                if (idx < list.size - 1) listBox.addView(View(this).apply { setBackgroundColor(0xFFF1EBE1.toInt()) }, LinearLayout.LayoutParams(MATCH, dp(1)))
            }
            Fonts.apply(listBox)
        }
        paintList()
        root.addView(NestedScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(11))
            background = topBorder(WHITE)
        }
        foot.addView(btn("Пропустить", "ghost") { dialog.dismiss(); openLoyaltyOrSend(card) }, LinearLayout.LayoutParams(WRAP, dp(50)).apply { rightMargin = dp(10) })
        foot.addView(payCta, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        Fonts.apply(root)
        dialog.setContentView(root)
        styleSheet(dialog)
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    /** Общая «шапка» листа: ручка 34×4, заголовок + подпись, ✕. */
    private fun sheetHead(root: LinearLayout, title: String, sub: String, onClose: () -> Unit): TextView {
        root.addView(View(this).apply {
            background = rounded(UiKit.LINE_STRONG, 2, null)
        }, LinearLayout.LayoutParams(dp(34), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(7)
        })
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            textSize = 16f
            letterSpacing = -0.02f
            weight(Fonts.STRONG)
            setTextColor(INK)
        })
        val subTv = TextView(this).apply {
            text = sub
            textSize = 10f
            weight(Fonts.MEDIUM)
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(2), 0, 0)
        }
        col.addView(subTv)
        head.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(closeButton("Закрыть", onClose), LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(head)
        return subTv
    }

    /** `.btn`: высота 50, радиус 14, 12.5/600. kind: primary | ink | ghost. */
    private fun btn(label: String, kind: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12.5f
        weight(Fonts.SEMIBOLD)
        gravity = Gravity.CENTER
        setPadding(dp(18), 0, dp(18), 0)
        when (kind) {
            "primary" -> { setTextColor(WHITE); background = rounded(BRAND, 14, null) }
            "ink" -> { setTextColor(WHITE); background = rounded(INK, 14, null) }
            else -> { setTextColor(INK); background = rounded(WHITE, 14, LINE) }
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Плитка выбора `.opt`: обводка ink у выбранной, галочка 14. */
    private fun choiceCell(title: String, sub: String?, on: Boolean, onClick: () -> Unit): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val base = rounded(WHITE, 11, if (on) INK else LINE)
        cell.background = if (!on) base else android.graphics.drawable.LayerDrawable(arrayOf(base, GradientDrawable().apply {
            setColor(0); cornerRadius = dp(10).toFloat(); setStroke(dp(1), INK)
        })).apply { setLayerInset(1, dp(1), dp(1), dp(1), dp(1)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title; textSize = 10.5f; weight(Fonts.MEDIUM)
            setTextColor(if (on) INK else UiKit.INK_2)
        })
        if (sub != null) col.addView(TextView(this).apply {
            text = sub; textSize = 10f; setTextColor(UiKit.MUTED)
        })
        cell.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (on) cell.addView(FrameLayout(this).apply {
            background = rounded(INK, 7, null)
            addView(icon(this@MainActivity, R.drawable.ic_check, 9f, WHITE), FrameLayout.LayoutParams(dp(9), dp(9), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { leftMargin = dp(6) })
        return cell
    }

    /**
     * Перед передачей в чек. Экрана оплаты в APK нет: способ оплаты, сумма, сдача и чек — это Эвотор POS.
     * Здесь решается только льгота по карте гостя (подарок / списание кэшбэка) — и только из резерва.
     */
    private fun openLoyaltyOrSend(card: CardInfo?) {
        if (Cart.isEmpty()) {
            notice = "Корзина пуста — добавьте позиции."
            noticeKind = Kind.WARN
            safeRender()
            return
        }
        val now = System.currentTimeMillis() / 1000
        val reserved = card != null && DiscountCalc.reserved(card, now)
        val giftPossible = reserved && card != null && card.freeAvailable > 0 && Cart.all().any { it.freeEligible }
        val cbPossible = reserved && card != null && card.cashbackKopecks > 0
        if (card == null || (!giftPossible && !cbPossible)) {
            sendReceipt(false, false, 0L)
            return
        }
        showLoyaltySheet(card, giftPossible, cbPossible)
    }

    /** Компактный лист «Лояльность»: подарок и кэшбэк из резерва → «В чек». Никаких способов оплаты. */
    private fun showLoyaltySheet(card: CardInfo, giftPossible: Boolean, cbPossible: Boolean) {
        dismissSheets()
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WHITE)
        }
        sheetHead(root, "Лояльность", "Чек · ${orderType.lowercase()} · Эвотор") { dialog.dismiss() }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(14))
        }
        root.addView(box, LinearLayout.LayoutParams(MATCH, WRAP))

        val now = System.currentTimeMillis() / 1000
        val eligibleLines = Cart.all().filter { it.freeEligible }
        val cheapest = DiscountCalc.cheapestEligible(eligibleLines.map { it.priceRub }) ?: BigDecimal.ZERO
        val cheapestName = eligibleLines.minByOrNull { it.priceRub }?.name

        // .pay-order: только справочная информация о госте и составе, без сумм к оплате.
        val order = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(9), dp(11), dp(9))
            background = rounded(UiKit.SURFACE_2, 11, UiKit.LINE_STRONG)
        }
        fun small(t: String, end: Boolean = false) = TextView(this).apply {
            text = t; textSize = 10f; setTextColor(UiKit.MUTED); letterSpacing = 0.02f
            if (end) gravity = Gravity.END
        }
        val oLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        oLeft.addView(small("Гость"))
        oLeft.addView(TextView(this).apply {
            text = card.cardCode.ifBlank { "по QR" }
            textSize = 11.5f; weight(Fonts.SEMIBOLD); setTextColor(INK)
        })
        order.addView(oLeft, LinearLayout.LayoutParams(0, WRAP, 1f))
        val oRight = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        oRight.addView(small("Позиций", true))
        oRight.addView(TextView(this).apply {
            text = cartLineCount(Cart.all()).toString()
            textSize = 11f; letterSpacing = 0.04f; weight(Fonts.STRONG); setTextColor(UiKit.INK_2); gravity = Gravity.END
        })
        order.addView(oRight)
        box.addView(order, LinearLayout.LayoutParams(MATCH, WRAP))

        val resExpiry = card.reservationExpiresAt
        val resText = when {
            card.reservationId == null -> "Резерв не создан: льгота не применится."
            resExpiry == 0L -> null
            resExpiry - now > 0 -> "Бонусы зарезервированы ещё ~${(resExpiry - now + 59) / 60} мин."
            else -> "Резерв истёк: бонус применится, только если его не занял другой чек."
        }
        resText?.let {
            box.addView(
                banner(it, if (DiscountCalc.reserved(card, now)) Kind.INFO else Kind.WARN, 11f),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) },
            )
        }

        loyaltyHint(card, CardProgress.of(card))?.let {
            box.addView(banner(it, Kind.INFO, 11f), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }

        var useGift = giftPossible
        var useCb = false
        // Один ваучер — предвыбран (кассир может снять), несколько — выбирает кассир.
        var selectedVoucher: ResolveVoucher? = card.vouchers.singleOrNull()
        fun block(title: String, first: String, firstSub: String?, second: String, secondSub: String?, firstOn: () -> Boolean, set: (Boolean) -> Unit) {
            val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            wrap.addView(TextView(this).apply {
                text = title.uppercase(); textSize = 10f; letterSpacing = 0.11f; weight(Fonts.STRONG); setTextColor(UiKit.INK_2)
                setPadding(0, dp(15), 0, dp(7))
            })
            val cells = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun paint() {
                cells.removeAllViews()
                cells.addView(choiceCell(first, firstSub, firstOn()) { set(true); paint() }, LinearLayout.LayoutParams(MATCH, WRAP))
                cells.addView(choiceCell(second, secondSub, !firstOn()) { set(false); paint() }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
                Fonts.apply(cells)
            }
            paint()
            wrap.addView(cells)
            box.addView(wrap)
        }
        if (giftPossible) {
            block("Подарок", "Применить подарок", "«${cheapestName ?: ""}» −${rub(cheapest)}", "Без подарка", null, { useGift }) { useGift = it }
        }
        if (cbPossible) {
            block("Кэшбэк", "Копить кэшбэк", null, "Списать кэшбэк", "до ${card.cashbackKopecks / 100} ₽", { !useCb }) { useCb = !it }
        }

        if (card.vouchers.isNotEmpty()) {
            val vWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            vWrap.addView(TextView(this).apply {
                text = "ВАУЧЕР"
                textSize = 10f; letterSpacing = 0.11f; weight(Fonts.STRONG); setTextColor(UiKit.INK_2)
                setPadding(0, dp(15), 0, dp(7))
            })
            val vCells = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun paintVouchers() {
                vCells.removeAllViews()
                vCells.addView(
                    choiceCell("Без ваучера", null, selectedVoucher == null) { selectedVoucher = null; paintVouchers() },
                    LinearLayout.LayoutParams(MATCH, WRAP),
                )
                for (v in card.vouchers) {
                    val label = if (v.isPercent) "${v.title} (−${v.value}%)" else "${v.title} (−${v.value} ₽)"
                    val blocked = !VoucherCalc.allowedTogether(v, useGift && giftPossible)
                    vCells.addView(
                        choiceCell(
                            if (blocked) "$label · не суммируется с подарком" else label,
                            null,
                            !blocked && selectedVoucher?.id == v.id,
                        ) { if (!blocked) { selectedVoucher = v; paintVouchers() } },
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) },
                    )
                }
                Fonts.apply(vCells)
            }
            paintVouchers()
            vWrap.addView(vCells)
            box.addView(vWrap)
        }

        box.addView(
            noteRow("Льготу применит Эвотор по резерву. Способ оплаты, сумму и чек считает POS."),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) },
        )

        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(11))
            background = topBorder(WHITE)
        }
        foot.addView(btn("В чек", "primary") {
            val free = useGift && giftPossible
            val spend = useCb && cbPossible
            val voucher = selectedVoucher?.takeIf { VoucherCalc.allowedTogether(it, free) }
            dialog.dismiss()
            sendReceipt(free, spend, if (spend) card.cashbackKopecks else 0L, voucher?.id ?: 0)
        }, LinearLayout.LayoutParams(MATCH, dp(50)))
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        Fonts.apply(root)
        dialog.setContentView(root)
        styleSheet(dialog)
        dialog.setOnDismissListener { if (checkoutDialog === dialog) checkoutDialog = null }
        checkoutDialog = dialog
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    /** Защита от двойного нажатия «В чек»: второй вызов, пока первый не завершился, игнорируется. */
    private var sendingReceipt = false

    /**
     * Финальная проверка корзины по последнему каталогу и передача позиций Эвотору.
     * Платная добавка без evotorUuid / удалённый товар / изменившаяся схема — чек не открываем.
     */
    private fun sendReceipt(free: Boolean, spend: Boolean, cashbackKopecks: Long, voucherId: Int = 0) {
        if (sendingReceipt) return
        if (Cart.isEmpty()) {
            notice = "Корзина пуста — добавьте позиции."
            noticeKind = Kind.WARN
            safeRender()
            return
        }
        val issues = ReceiptValidator.validate(Cart.all(), products)
        if (issues.isNotEmpty()) {
            showReceiptIssues(issues)
            return
        }
        sendingReceipt = true
        // Выгода комбо должна быть уже записана: её применяет DiscountIntegrationService.
        CheckoutChoice.saveCombo(this, comboDiscountRub())
        // Номер очереди обязан быть в хранилище ДО печати (PrintExtra срабатывает после оплаты).
        // Запрос синхронный с коротким таймаутом; ошибка/таймаут — чек печатается без номера.
        requestQueueNumberBlocking()
        SellLauncher.openSellReceipt(this, free, spend, cashbackKopecks, voucherId) { outcome ->
            sendingReceipt = false
            if (outcome.ok) {
                // Чек открыт, но оплата не подтверждена: заказ ждёт (PENDING), номер не тратится.
                markPendingOrder()
                PendingPayment.set(this)
                // Уходим на экран Эвотор; по возврату onResume решит: успех → QUEUE, отмена → ORDER+корзина.
                screen = Screen.ORDER
            } else {
                notice = outcome.message
                noticeKind = Kind.ERROR
                if (outcome.catalogRefresh) loadProducts(userInitiated = true)
                if (screen == Screen.ORDER) safeRender()
            }
        }
    }

    /**
     * Запросить публичный номер заказа у backend **до** открытия чека.
     *
     * Вызывается из фонового потока продажи: сеть не должна идти в UI-поток, но и «успеет
     * в фоне» не годится — к моменту PrintExtra номер обязан лежать в хранилище. Ждём
     * не дольше [QUEUE_NUMBER_WAIT_MS]; не дождались — печатаем чек без номера.
     */
    private fun requestQueueNumberBlocking() {
        QueueNumberStore.clear(this)
        SalePrintStore.clear(this)
        val ready = java.util.concurrent.CountDownLatch(1)
        val fetched = java.util.concurrent.atomic.AtomicReference<QueueNumber?>()
        thread(name = "queue-number") {
            fetched.set(try { QueueNumberApi.issue() } catch (t: Throwable) { Log.w(TAG, "queue_number", t); null })
            ready.countDown()
        }
        val waited = ready.await(QUEUE_NUMBER_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        val number = fetched.get()
        if (number != null) {
            QueueNumberStore.save(this, number)
            Log.i(TAG, "queue_number_ready number=${number.publicNumber}")
        } else {
            Log.i(TAG, "queue_number_skipped reason=${if (waited) "offline" else "timeout"}")
        }
    }

    /**
     * Заказ ждёт оплаты: состав фиксируется сейчас, номер выдаётся только после подтверждения.
     * Локально на терминале, backend не участвует.
     */
    private fun markPendingOrder() {
        try {
            val lines = Cart.all()
            if (lines.isEmpty()) return
            OrderQueueStore.addPending(
                this,
                lines.map { l ->
                    val q = l.quantity.stripTrailingZeros().toPlainString()
                    if (q == "1") l.name else "${l.name} × $q"
                },
                cartTotalRub(),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "queue pending", t)
        }
    }

    /** Оплата подтверждена (чек закрыт в POS): присвоить номер и поставить в очередь бариста. */
    private fun confirmPendingOrder(): QueueOrder? {
        return try {
            val id = OrderQueueStore.pendingId(this) ?: return null
            // Номер на чеке выдаёт сервер; локальный счётчик — только аварийный fallback.
            val serverNumber = QueueNumberStore.peek(this)?.publicNumber?.toIntOrNull()
            OrderQueueStore.confirm(this, id, shiftInfo?.number, publicNumber = serverNumber)
        } catch (t: Throwable) {
            Log.e(TAG, "queue confirm", t)
            null
        }
    }

    /**
     * Перезапросить карту в фоне. После продажи backend видит новые чашки только после poll,
     * поэтому обновление идёт и сразу, и по таймеру (C04).
     */
    private fun refreshCardAsync(notifyOnChange: Boolean = false) {
        val current = CardSession.get(this)?.takeIf { it.code.isNotEmpty() } ?: return
        if (cardRefreshing) return
        cardRefreshing = true
        thread(name = "card-refresh") {
            val result = try {
                CardSession.refreshFromServer(this) { LoyaltyApi.resolve(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "card_refresh_failed", t)
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                cardRefreshing = false
                when {
                    result == null -> Unit
                    result.dropped -> {
                        notice = "Карта больше недоступна. Привяжите карту заново."
                        noticeKind = Kind.WARN
                        safeRender()
                    }
                    result.updated && result.info != null -> {
                        val fresh = result.info
                        if (notifyOnChange && CardRefresh.isNotable(current, fresh)) {
                            val p = CardProgress.of(fresh)
                            notice = if (p.giftAvailable) "Карта обновлена: подарок доступен"
                            else "Карта обновлена: ${p.cups} из ${p.goal} чашек"
                            noticeKind = Kind.INFO
                        }
                        safeRender()
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Фоновый опрос карты: пока экран виден и карта привязана, не чаще [CardPoll.INTERVAL_MS]. */
    private val cardPollTick = object : Runnable {
        override fun run() {
            if (!cardPollActive) return
            val attached = CardSession.get(this@MainActivity)?.code?.isNotEmpty() == true
            if (!attached) { cardPollActive = false; return }
            refreshCardAsync(notifyOnChange = true)
            cardPollHandler.postDelayed(this, CardPoll.INTERVAL_MS)
        }
    }

    private fun startCardPoll() {
        if (cardPollActive) return
        if (CardSession.get(this)?.code?.isNotEmpty() != true) return
        cardPollActive = true
        cardPollHandler.postDelayed(cardPollTick, CardPoll.INTERVAL_MS)
    }

    private fun stopCardPoll() {
        cardPollActive = false
        cardPollHandler.removeCallbacks(cardPollTick)
    }

    /** Чек остался открытым: оплаты не было, PENDING удаляем — номер не израсходован. */
    private fun cancelPendingOrder() {
        try {
            val id = OrderQueueStore.pendingId(this) ?: return
            OrderQueueStore.cancelPending(this, id)
        } catch (t: Throwable) {
            Log.e(TAG, "queue cancel", t)
        }
    }

    /** Позиции, которые нельзя провести в чеке: показываем причину и после подтверждения убираем их. */
    private fun showReceiptIssues(issues: List<ReceiptIssue>) {
        Log.w(
            TAG,
            "receipt_blocked count=${issues.size} reasons=${issues.map { it.reason }.distinct()} " +
                "options_without_uuid=${issues.count { it.reason == "option_without_uuid" }}",
        )
        val text = issues.take(8).joinToString("\n") { "• ${it.name}: ${it.message}" }
        AlertDialog.Builder(this)
            .setTitle("Позиции не попадут в чек")
            .setMessage(text)
            .setPositiveButton("Убрать и обновить") { _, _ ->
                issues.forEach { Cart.remove(it.cartKey) }
                onCartChanged()
                loadProducts(userInitiated = true)
                safeRender()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ── Экран SHIFT: хаб смены (index shift-page) ──

    /**
     * Block 11: оболочка «Смена» 1:1 по структуре index.
     * Цифры выручки/чеков НЕ рисуем из воздуха — пока нет API смены.
     * Подэкраны timesheet/ops/… — заглушки до блоков 12+.
     */

    private var shiftInfo: ru.sixthcup.evotor.shift.EvotorShift.Info? = null
    private var shiftBusy = false

    private fun loadShiftInfo() {
        ru.sixthcup.evotor.shift.EvotorShift.load(this) { info ->
            val changed = shiftInfo != info
            shiftInfo = info
            if (changed && (screen == Screen.SHIFT || screen == Screen.ORDER || screen == Screen.CARD)) safeRender()
        }
    }

    private fun shiftOpenState(): Boolean? = when (shiftInfo?.state) {
        ru.sixthcup.evotor.shift.EvotorShift.State.OPEN -> true
        ru.sixthcup.evotor.shift.EvotorShift.State.CLOSED -> false
        else -> null
    }

    /** `.shift-local-back`: иконка-стрелка + подпись. */
    private fun shiftLocalBack(label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        isClickable = true
        isFocusable = true
        addView(icon(this@MainActivity, R.drawable.ic_back, 16f, BRAND))
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = 12.5f; weight(Fonts.SEMIBOLD); setTextColor(BRAND)
            setPadding(dp(6), 0, dp(8), 0)
        })
        setOnClickListener { onClick(); safeRender() }
    }

    private fun toastMsg(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    /** Внесение / изъятие: сумма + комментарий → команда Эвотора. */
    private fun showCashOpSheet(out: Boolean) {
        dismissSheets()
        val dialog = BottomSheetDialog(this)
        val rootV = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(WHITE) }
        sheetHead(rootV, if (out) "Изъятие" else "Внесение", "Наличные · проведёт Эвотор") { dialog.dismiss() }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(14)) }
        rootV.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        val amount = android.widget.EditText(this).apply {
            hint = "Сумма, ₽"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 22f; weight(Fonts.BOLD); setTextColor(INK)
            setPadding(dp(12), 0, dp(12), 0); background = rounded(UiKit.SURFACE_2, 12, LINE)
        }
        body.addView(amount, LinearLayout.LayoutParams(MATCH, dp(56)))
        val comment = android.widget.EditText(this).apply {
            hint = "Комментарий (необязательно)"; textSize = 14f; setTextColor(INK)
            setPadding(dp(12), 0, dp(12), 0); background = rounded(WHITE, 12, LINE); setSingleLine()
        }
        body.addView(comment, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(10) })
        val err = TextView(this).apply { textSize = 12f; setTextColor(UiKit.DANGER); setPadding(0, dp(8), 0, 0); visibility = View.GONE }
        body.addView(err)
        body.addView(btn(if (out) "Изъять" else "Внести", "primary") {
            val sum = amount.text.toString().toBigDecimalOrNull()
            if (sum == null || sum.signum() <= 0) {
                err.text = "Введите сумму больше нуля"; err.visibility = View.VISIBLE
            } else if (!shiftBusy) {
                shiftBusy = true
                val cb: (String?) -> Unit = { e ->
                    shiftBusy = false
                    if (e == null) { dialog.dismiss(); toastMsg(if (out) "Изъятие проведено" else "Внесение проведено") }
                    else { err.text = e; err.visibility = View.VISIBLE }
                }
                if (out) ru.sixthcup.evotor.shift.EvotorShift.cashOut(this, sum, comment.text.toString(), cb)
                else ru.sixthcup.evotor.shift.EvotorShift.cashIn(this, sum, comment.text.toString(), cb)
            }
        }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(12) })
        dialog.setContentView(rootV)
        styleSheet(dialog)
        Fonts.apply(rootV)
        dialog.show()
    }


    // ── Данные смены с сервера: загрузка / ошибка / пусто / готово ──
    private class Remote<T> {
        var state: String = "idle"   // idle | loading | error | empty | ready
        var data: T? = null
        var error: String? = null
        var key: String? = null
    }
    private val tsRemote = Remote<Map<String, ShiftApi.TsDay>>()
    private val wishRemote = Remote<ShiftApi.Wishes>()
    private val teamRemote = Remote<List<ShiftApi.Member>>()
    private val summaryRemote = Remote<ShiftApi.Summary>()
    private val breakRemote = Remote<ShiftApi.BreakRec?>()
    private var wishSending = false

    /** Запускает загрузку, если для [key] данных ещё нет (или [force]). Ответ — в UI-потоке с перерисовкой. */
    private fun <T> ensureLoaded(r: Remote<T>, key: String, force: Boolean = false, isEmpty: (T) -> Boolean, fetch: () -> ShiftApi.Res<T>, onData: (T) -> Unit = {}) {
        if (r.state == "loading" && r.key == key) return
        if (!force && r.key == key && r.state != "idle") return
        r.state = "loading"; r.key = key; r.error = null
        Thread {
            val res = fetch()
            runOnUiThread {
                if (r.key != key) return@runOnUiThread
                when (res) {
                    is ShiftApi.Res.Ok -> { r.data = res.value; r.state = if (isEmpty(res.value)) "empty" else "ready"; onData(res.value) }
                    is ShiftApi.Res.Fail -> { r.data = null; r.state = "error"; r.error = res.message }
                }
                if (screen == Screen.SHIFT) safeRender()
            }
        }.start()
    }

    /**
     * Статус загрузки в виде карточки. true → есть что рисовать (state ready).
     * [emptyTitle]/[emptyText] — честное «нет данных» без выдуманных строк.
     */
    private fun <T> remoteStatus(r: Remote<T>, what: String, emptyText: String, retry: () -> Unit): Boolean {
        if (r.state == "ready") return true
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
            background = rounded(WHITE, 14, LINE)
        }
        val (title, text) = when (r.state) {
            "error" -> "Не удалось загрузить: $what" to (r.error ?: "Проверьте подключение")
            "empty" -> "Нет данных" to emptyText
            else -> "Загрузка…" to what
        }
        card.addView(TextView(this).apply { this.text = title; textSize = 14f; weight(Fonts.STRONG); setTextColor(INK); gravity = Gravity.CENTER })
        card.addView(TextView(this).apply { this.text = text; textSize = 12f; setTextColor(UiKit.MUTED); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        if (r.state != "loading") card.addView(btn("Обновить", "ghost") { retry() }, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(12) })
        root.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        return false
    }

    private fun monthKey(y: Int, m0: Int) = "%04d-%02d".format(y, m0 + 1)
    private fun monthRu(m0: Int) = arrayOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")[m0]

    private fun loadTimesheet(force: Boolean = false) {
        val key = monthKey(tsYear, tsMonth)
        val last = java.util.Calendar.getInstance().apply { set(tsYear, tsMonth, 1) }.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        ensureLoaded(tsRemote, key, force, isEmpty = { it.isEmpty() }, fetch = {
            when (val r = ShiftApi.timesheet("$key-01", "$key-%02d".format(last))) {
                is ShiftApi.Res.Ok -> ShiftApi.Res.Ok(r.value.associateBy { it.date })
                is ShiftApi.Res.Fail -> r
            }
        })
    }

    private fun loadWishes(force: Boolean = false) {
        val key = monthKey(wishYear, wishMonth)
        ensureLoaded(wishRemote, key, force, isEmpty = { false }, fetch = { ShiftApi.wishes(key) }, onData = { w ->
            wishSelected.clear(); w.days.mapNotNull { it.takeLast(2).toIntOrNull() }.forEach { wishSelected.add(it) }
        })
    }

    private fun dayText(d: ShiftApi.TsDay?): String = when (d?.status) {
        null -> "—"
        "off" -> "вых"
        "vacation" -> "отп"
        "sick" -> "бол"
        else -> d.from?.take(2)?.let { f -> d.to?.take(2)?.let { t -> "$f–$t" } } ?: "смена"
    }

    private fun dayLong(d: ShiftApi.TsDay?): String = when (d?.status) {
        null -> "В этот день смен нет."
        "off" -> "Выходной"
        "vacation" -> "Отпуск"
        "sick" -> "Больничный"
        else -> "Смена" + (if (d.from != null && d.to != null) " ${d.from}–${d.to}" else "")
    } + (d?.note?.let { "\n$it" } ?: "")

    private fun closeButtonBack(label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        isClickable = true
        isFocusable = true
        addView(icon(this@MainActivity, R.drawable.ic_back, 16f, BRAND))
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = 12.5f; weight(Fonts.SEMIBOLD); setTextColor(BRAND)
            setPadding(dp(6), 0, dp(12), 0)
        })
        setOnClickListener { onClick() }
    }

    private fun renderShift() {
        if (shiftInfo == null) loadShiftInfo()
        topBar.setPadding(dp(12), dp(10), dp(12), dp(8))
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(closeButtonBack("К заказам") {
            shiftView = "home"
            notice = null
            screen = Screen.ORDER
            safeRender()
        })
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "Смена"
            textSize = 16f
            setOnest(true)
            setTextColor(INK)
        })
        titleCol.addView(TextView(this).apply {
            text = when (shiftView) {
                "ops" -> "Операции"
                "stats" -> "Моя статистика"
                "team" -> "Команда"
                "timesheet" -> "Табель"
                "wishes" -> "Пожелания"
                "breaks" -> "Перерывы"
                "close" -> "Закрытие смены"
                else -> when (shiftOpenState()) { true -> "Открыта"; false -> "Не открыта"; null -> "Статус неизвестен" }
            }
            textSize = 10f
            setTextColor(UiKit.MUTED)
        })
        head.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(when (shiftOpenState()) { true -> UiKit.SUCCESS; false -> UiKit.DANGER; null -> UiKit.MUTED })
            }
        }, LinearLayout.LayoutParams(dp(8), dp(8)))
        topBar.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        when (shiftView) {
            "home" -> renderShiftHome()
            "timesheet" -> renderShiftTimesheet()
            "wishes" -> renderShiftWishes()
            "team" -> renderShiftTeam()
            "ops" -> renderShiftOps()
            "stats" -> renderShiftStats()
            "breaks" -> renderShiftBreaks()
            "close" -> renderShiftClose()
            else -> renderShiftStub(shiftView)
        }
    }

    private fun renderShiftHome() {
        // hero — без выдуманных метрик
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = rounded(WHITE, 18, LINE)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val st = shiftOpenState()
        top.addView(TextView(this).apply {
            text = when (st) { true -> "●  Смена открыта"; false -> "●  Смена не открыта"; null -> "●  Статус смены неизвестен" }
            textSize = 11f
            weight(Fonts.SEMIBOLD)
            setTextColor(when (st) { true -> UiKit.SUCCESS; false -> UiKit.DANGER; null -> UiKit.MUTED })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(TextView(this).apply {
            text = shiftInfo?.number?.let { "№ $it" } ?: "Эвотор"
            textSize = 10f
            setTextColor(UiKit.MUTED)
        })
        hero.addView(top)
        hero.addView(TextView(this).apply {
            text = when (st) {
                true -> "Фискальная смена открыта на кассе. Итоги смены — в разделе «Моя статистика»."
                false -> "Откройте смену, чтобы принимать заказы и проводить операции."
                null -> "Не удалось прочитать состояние смены у Эвотора. Проверьте разрешения приложения."
            }
            textSize = 12f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(10), 0, 0)
        })
        if (st == false) {
            hero.addView(btn("Открыть смену", "primary") {
                if (!shiftBusy) {
                    shiftBusy = true
                    ru.sixthcup.evotor.shift.EvotorShift.openShift(this) { e ->
                        shiftBusy = false
                        if (e != null) toastMsg(e)
                        loadShiftInfo()
                    }
                }
            }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(12) })
        }
        root.addView(hero, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        // action grid 2 cols
        fun action(title: String, sub: String, view: String): View {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(WHITE, 14, LINE)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    shiftView = view
                    safeRender()
                }
            }
            card.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setOnest(true)
                setTextColor(INK)
            })
            card.addView(TextView(this).apply {
                text = sub
                textSize = 10.5f
                setTextColor(UiKit.MUTED)
                setPadding(0, dp(3), 0, 0)
            })
            return card
        }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun pair(a: View, b: View?) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(a, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = if (b != null) dp(8) else 0 })
            if (b != null) row.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
            grid.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        pair(action("Операции", "Внесение, изъятие", "ops"), action("Моя статистика", "Чеки и выручка", "stats"))
        pair(action("Команда", "Кто на смене", "team"), action("Табель", "Месяц и пожелания", "timesheet"))
        pair(action("Перерывы", "Рекомендации и учёт", "breaks"), null)
        root.addView(grid)

        root.addView(TextView(this).apply {
            text = "Закрыть смену"
            textSize = 13f
            weight(Fonts.SEMIBOLD)
            gravity = Gravity.CENTER
            setTextColor(UiKit.DANGER)
            setPadding(0, dp(16), 0, dp(8))
            isClickable = true
            setOnClickListener {
                shiftView = "close"
                safeRender()
            }
        })
        root.addView(TextView(this).apply {
            text = "Смена и фискальные операции связаны с Эвотором. 6.7 показывает статус, цифры и подготовку операций."
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(4), 0, dp(8))
        })

        // service: keep old «Ещё» tools
        bottomBar.setPadding(dp(12), dp(6), dp(12), dp(10))
        bottomBar.addView(TextView(this).apply {
            text = "Сервис · каталог / карта"
            textSize = 12.5f
            setOnest(true)
            gravity = Gravity.CENTER
            setTextColor(INK)
            background = rounded(WHITE, 14, LINE)
            isClickable = true
            setOnClickListener { v ->
                val card = CardSession.get(this@MainActivity)?.takeIf { it.code.isNotEmpty() }
                showMore(v, card)
            }
        }, LinearLayout.LayoutParams(MATCH, dp(48)))
    }

    private fun renderShiftTimesheet() {
        root.addView(shiftLocalBack("Назад к смене") { shiftView = "home" }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(10) })

        val store = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(10)) }
        store.addView(FrameLayout(this).apply {
            background = rounded(WHITE, 8, LINE)
            addView(icon(this@MainActivity, R.drawable.ic_store, 16f, UiKit.INK_2), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(9) })
        val storeCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        storeCol.addView(TextView(this).apply { text = "6.7 Coffee"; textSize = 12f; weight(Fonts.STRONG); setTextColor(INK) })
        storeCol.addView(TextView(this).apply { text = "Только ваша точка"; textSize = 10f; setTextColor(UiKit.MUTED) })
        store.addView(storeCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(store)

        loadTimesheet()
        val data = tsRemote.data.orEmpty()

        fun navBtn(prev: Boolean, onClick: () -> Unit): View = FrameLayout(this).apply {
            background = rounded(WHITE, 9, LINE)
            isClickable = true
            contentDescription = if (prev) "Предыдущий месяц" else "Следующий месяц"
            addView(icon(this@MainActivity, R.drawable.ic_chevron_sm, 16f, UiKit.INK_2).apply { if (prev) rotation = 180f }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            setOnClickListener { onClick() }
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(navBtn(true) {
            if (tsMonth == 0) { tsMonth = 11; tsYear -= 1 } else tsMonth -= 1
            tsSelectedDay = 1; safeRender()
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        mid.addView(TextView(this).apply { text = "${monthRu(tsMonth)} $tsYear"; textSize = 14f; weight(Fonts.STRONG); setTextColor(INK); gravity = Gravity.CENTER })
        mid.addView(TextView(this).apply { text = "только просмотр"; textSize = 10f; setTextColor(UiKit.MUTED); gravity = Gravity.CENTER })
        bar.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(navBtn(false) {
            if (tsMonth == 11) { tsMonth = 0; tsYear += 1 } else tsMonth += 1
            tsSelectedDay = 1; safeRender()
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

        // Календарь показываем всегда (можно листать месяцы); статус загрузки — карточкой под ним.
        val wd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { d ->
            wd.addView(TextView(this).apply { text = d; textSize = 10f; gravity = Gravity.CENTER; setTextColor(UiKit.MUTED) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(wd, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })

        val cal = java.util.Calendar.getInstance().apply { set(tsYear, tsMonth, 1) }
        val daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val lead = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        val today = java.util.Calendar.getInstance()
        val isThisMonth = today.get(java.util.Calendar.YEAR) == tsYear && today.get(java.util.Calendar.MONTH) == tsMonth
        val todayDay = today.get(java.util.Calendar.DAY_OF_MONTH)
        if (tsSelectedDay > daysInMonth) tsSelectedDay = daysInMonth
        val mk = monthKey(tsYear, tsMonth)

        var day = 1
        var cell = 0
        repeat((lead + daysInMonth + 6) / 7) {
            val week = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) {
                if (cell < lead || day > daysInMonth) {
                    week.addView(View(this), LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                } else {
                    val d = day
                    val selected = d == tsSelectedDay
                    val isToday = isThisMonth && d == todayDay
                    val entry = data["$mk-%02d".format(d)]
                    val cellV = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(2), dp(4), dp(2), dp(4))
                        background = when {
                            selected -> rounded(BRAND, 10, BRAND)
                            isToday -> rounded(UiKit.BRAND_SOFT, 10, BRAND)
                            else -> rounded(WHITE, 10, LINE)
                        }
                        isClickable = true
                        setOnClickListener { tsSelectedDay = d; safeRender() }
                    }
                    cellV.addView(TextView(this).apply { text = d.toString(); textSize = 12f; weight(Fonts.STRONG); gravity = Gravity.CENTER; setTextColor(if (selected) WHITE else INK) })
                    cellV.addView(TextView(this).apply {
                        text = dayText(entry); textSize = 10f; gravity = Gravity.CENTER; maxLines = 1
                        setTextColor(if (selected) 0xB3FFFFFF.toInt() else if (entry?.status == "work") BRAND else UiKit.MUTED)
                    })
                    week.addView(cellV, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                    day++
                }
                cell++
            }
            root.addView(week, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        root.addView(TextView(this).apply {
            text = "День $tsSelectedDay · ${monthRu(tsMonth).lowercase()}"; textSize = 13f; weight(Fonts.STRONG); setTextColor(INK); setPadding(0, dp(14), 0, dp(4))
        })
        if (tsRemote.state == "ready") {
            root.addView(TextView(this).apply { text = dayLong(data["$mk-%02d".format(tsSelectedDay)]); textSize = 12f; setTextColor(INK) })
        } else {
            remoteStatus(tsRemote, "табель за ${monthRu(tsMonth).lowercase()}", "На этот месяц график для вашей точки не опубликован.") { loadTimesheet(true); safeRender() }
        }

        root.addView(btn("Пожелания по выходным", "ghost") { shiftView = "wishes"; safeRender() }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(14) })
        root.addView(TextView(this).apply {
            text = "Табель на кассе только для просмотра. Правки графика — в админке."
            textSize = 10.5f; setTextColor(UiKit.MUTED); setPadding(0, dp(10), 0, 0)
        })
    }

    private fun renderShiftWishes() {
        if (wishYear == 0) {
            val c = java.util.Calendar.getInstance(); c.add(java.util.Calendar.MONTH, 1)
            wishYear = c.get(java.util.Calendar.YEAR); wishMonth = c.get(java.util.Calendar.MONTH)
        }
        root.addView(shiftLocalBack("Назад к табелю") { shiftView = "timesheet" }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(10) })
        loadWishes()
        val w = wishRemote.data

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(10)) }
        val hCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hCol.addView(TextView(this).apply { text = "Пожелания на"; textSize = 10f; setTextColor(UiKit.MUTED) })
        hCol.addView(TextView(this).apply { text = "${monthRu(wishMonth)} $wishYear"; textSize = 16f; weight(Fonts.STRONG); setTextColor(INK) })
        head.addView(hCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (w != null) {
            val (label, bg, fg) = when (w.status) {
                "sent" -> Triple("Отправлено", UiKit.BRAND_SOFT, BRAND)
                "approved" -> Triple("Принято", UiKit.SUCCESS_SOFT, 0xFF15613D.toInt())
                "rejected" -> Triple("Отклонено", UiKit.WARN_SOFT, UiKit.WARN)
                "closed" -> Triple("Приём закрыт", UiKit.SURFACE_2, UiKit.MUTED)
                else -> Triple("Открыто", UiKit.SUCCESS_SOFT, 0xFF15613D.toInt())
            }
            head.addView(TextView(this).apply {
                text = label; textSize = 10f; weight(Fonts.STRONG); setTextColor(fg)
                setPadding(dp(8), dp(4), dp(8), dp(4)); background = rounded(bg, 999, null)
            })
        }
        root.addView(head)

        if (!remoteStatus(wishRemote, "пожелания", "Приём пожеланий на этот месяц не открыт.") { loadWishes(true); safeRender() }) return
        w!!

        val rules = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = rounded(UiKit.SURFACE_2, 12, LINE); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        fun rule(label: String, value: String) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply { text = label; textSize = 10f; setTextColor(UiKit.MUTED) })
            col.addView(TextView(this).apply { text = value; textSize = 12f; weight(Fonts.STRONG); setTextColor(INK) })
            rules.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        rule("Приём до", w.deadline ?: "не указан")
        rule("Лимит дат", w.limit?.toString() ?: "не указан")
        root.addView(rules, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })

        root.addView(TextView(this).apply {
            text = "Выберите даты. Это пожелание, а не назначение выходных — решение принимает руководитель."
            textSize = 12f; setTextColor(UiKit.MUTED); setPadding(0, 0, 0, dp(10))
        })

        val wd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { d ->
            wd.addView(TextView(this).apply { text = d; textSize = 10f; gravity = Gravity.CENTER; setTextColor(UiKit.MUTED) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(wd, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })

        val cal = java.util.Calendar.getInstance().apply { set(wishYear, wishMonth, 1) }
        val daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val lead = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        val editable = w.editable && !wishSending
        var day = 1
        var cell = 0
        repeat((lead + daysInMonth + 6) / 7) {
            val week = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) {
                if (cell < lead || day > daysInMonth) {
                    week.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                } else {
                    val d = day
                    val on = d in wishSelected
                    week.addView(TextView(this).apply {
                        text = d.toString(); textSize = 13f; gravity = Gravity.CENTER; weight(Fonts.STRONG)
                        setTextColor(if (on) WHITE else INK)
                        background = rounded(if (on) INK else WHITE, 10, if (on) INK else LINE)
                        alpha = if (editable) 1f else 0.7f
                        isClickable = editable
                        if (editable) setOnClickListener {
                            if (d in wishSelected) wishSelected.remove(d)
                            else if (w.limit != null && wishSelected.size >= w.limit) toastMsg("Лимит дат: ${w.limit}")
                            else wishSelected.add(d)
                            safeRender()
                        }
                    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                    day++
                }
                cell++
            }
            root.addView(week, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        root.addView(TextView(this).apply {
            text = "Выбрано ${wishSelected.size}" + if (wishSelected.isEmpty()) "" else ": " + wishSelected.joinToString(", ")
            textSize = 12.5f; weight(Fonts.STRONG); setTextColor(INK); setPadding(0, dp(12), 0, dp(4))
        })
        if (w.editable) {
            root.addView(btn(if (wishSending) "Отправка…" else "Отправить пожелания", if (wishSelected.isEmpty()) "ghost" else "primary") {
                if (wishSelected.isNotEmpty() && !wishSending) {
                    wishSending = true
                    val key = monthKey(wishYear, wishMonth)
                    val days = wishSelected.map { "$key-%02d".format(it) }
                    safeRender()
                    Thread {
                        val res = ShiftApi.sendWishes(key, days)
                        runOnUiThread {
                            wishSending = false
                            when (res) {
                                is ShiftApi.Res.Ok -> { wishRemote.data = res.value; wishRemote.state = "ready" ; toastMsg("Пожелания отправлены") }
                                is ShiftApi.Res.Fail -> toastMsg(res.message)
                            }
                            if (screen == Screen.SHIFT) safeRender()
                        }
                    }.start()
                }
            }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(14) })
        }
        root.addView(TextView(this).apply {
            text = "Сроки, лимит и результат рассмотрения задаёт сервер. Приложение не назначает выходные само."
            textSize = 10.5f; setTextColor(UiKit.MUTED); setPadding(0, dp(12), 0, 0)
        })
    }

    private fun renderShiftTeam() {
        root.addView(shiftLocalBack("Назад к смене") { shiftView = "home" }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(10) })
        ensureLoaded(teamRemote, "team", isEmpty = { it.isEmpty() }, fetch = { ShiftApi.team() })
        val meta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12)) }
        meta.addView(TextView(this).apply { text = "Текущая смена"; textSize = 11f; setTextColor(UiKit.MUTED) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        val list = teamRemote.data.orEmpty()
        if (teamRemote.state == "ready") meta.addView(TextView(this).apply { text = "${list.size} ${plural(list.size, "сотрудник", "сотрудника", "сотрудников")}"; textSize = 11f; weight(Fonts.STRONG); setTextColor(INK) })
        root.addView(meta)
        if (!remoteStatus(teamRemote, "команда", "Сервер не вернул сотрудников на текущей смене.") { ensureLoaded(teamRemote, "team", true, { it.isEmpty() }, { ShiftApi.team() }); safeRender() }) return
        list.forEach { m ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(WHITE, 14, LINE) }
            row.addView(AvatarView(this).apply { bind(m.name.first().uppercaseChar().toString(), solid = false, textSp = 13f) }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply { text = m.name; textSize = 13f; weight(Fonts.STRONG); setTextColor(INK) })
            m.role?.let { r -> col.addView(TextView(this).apply { text = r; textSize = 10.5f; setTextColor(UiKit.MUTED) }) }
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (m.receipts != null || m.revenueRub != null) {
                val f = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
                m.receipts?.let { n -> f.addView(TextView(this).apply { text = n.toString(); textSize = 13f; weight(Fonts.BOLD); setTextColor(INK); gravity = Gravity.END }) }
                m.revenueRub?.let { n -> f.addView(TextView(this).apply { text = rub(BigDecimal(n)); textSize = 10.5f; setTextColor(UiKit.MUTED); gravity = Gravity.END }) }
                row.addView(f)
            }
            root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        root.addView(TextView(this).apply { text = "Без рейтингов и сравнений. Данные только за текущую смену."; textSize = 10.5f; setTextColor(UiKit.MUTED); setPadding(0, dp(8), 0, 0) })
    }

    /**
     * Block 15: Операции (index ops).
     * Фискальные действия выполняет Эвотор. Здесь — список и пояснение, без фейковых сумм «в кассе».
     * Прямой вызов Integration Library (pay in/out) — отдельный блок после проверки API.
     */
    private fun renderShiftOps() {
        root.addView(shiftLocalBack("Назад к смене") { shiftView = "home" }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(10) })

        // cash card — no invented amount
        val cash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(WHITE, 16, LINE)
        }
        cash.addView(TextView(this).apply {
            text = "Наличные в кассе"
            textSize = 10f
            setTextColor(UiKit.MUTED)
        })
        cash.addView(TextView(this).apply {
            text = "см. Эвотор"
            textSize = 22f
            setOnest(true)
            setTextColor(INK)
            setPadding(0, dp(4), 0, 0)
        })
        cash.addView(TextView(this).apply {
            text = "Сумму показывает штатный экран терминала"
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(cash, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        data class Op(val title: String, val sub: String, val key: String)
        val ops = listOf(
            Op("Внесение", "Наличные в кассу", "in"),
            Op("Изъятие", "Выплата или изъятие наличных", "out"),
            Op("Инкассация", "Передача наличных", "collect"),
            Op("X-отчёт", "Состояние смены без закрытия", "xreport"),
        )
        ops.forEach { op ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = rounded(WHITE, 14, LINE)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    when (op.key) {
                        "in" -> showCashOpSheet(false)
                        "out" -> showCashOpSheet(true)
                        else -> toastMsg("«${op.title}» выполняется на штатном экране Эвотора: Меню → Касса.")
                    }
                }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = op.title
                textSize = 13f
                setOnest(true)
                setTextColor(INK)
            })
            col.addView(TextView(this).apply {
                text = op.sub
                textSize = 10.5f
                setTextColor(UiKit.MUTED)
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(icon(this, R.drawable.ic_chevron_sm, 16f, UiKit.MUTED))
            root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }

        root.addView(TextView(this).apply {
            text = "Фискальные операции проводит Эвотор. Здесь — подготовка и подтверждение."
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun renderShiftStats() {
        root.addView(shiftLocalBack("Назад к смене") { shiftView = "home" }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(10) })
        ensureLoaded(summaryRemote, "summary", isEmpty = { it.receipts == null && it.revenueRub == null }, fetch = { ShiftApi.summary() })

        // Один блок «текущая смена»: цифры сервера, если пришли; иначе — локальный счётчик этого терминала.
        val server = summaryRemote.data?.takeIf { summaryRemote.state == "ready" }
        val local = OrderQueueStore.tally(this, shiftInfo?.number)
        val useServer = server?.receipts != null
        val receipts = if (useServer) server!!.receipts!! else local.receipts
        val revenue: String = if (useServer) (server!!.revenueRub?.let { rub(BigDecimal(it)) } ?: "—") else rub(local.sumRub)
        val avg: String = if (useServer) (server!!.avgRub?.let { rub(BigDecimal(it)) } ?: "—") else (local.avgRub?.let { rub(it) } ?: "—")

        root.addView(TextView(this).apply {
            text = if (useServer) "Текущая смена · данные сервера" else "Текущая смена · считает этот терминал"
            textSize = 11f; setTextColor(UiKit.MUTED); setPadding(0, 0, 0, dp(12))
        })
        fun metric(value: String, label: String): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(WHITE, 14, LINE)
            addView(TextView(this@MainActivity).apply { text = value; textSize = 20f; weight(Fonts.BOLD); setTextColor(INK) })
            addView(TextView(this@MainActivity).apply { text = label; textSize = 10f; setTextColor(UiKit.MUTED); setPadding(0, dp(2), 0, 0) })
        }
        val g1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        g1.addView(metric(receipts.toString(), plural(receipts, "чек", "чека", "чеков")), LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
        g1.addView(metric(revenue, "выручка"), LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(g1, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        val g2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        g2.addView(metric(avg, "средний чек"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
        server?.drinksPct?.let { g2.addView(metric("$it%", "напитки"), LinearLayout.LayoutParams(0, WRAP, 1f)) }
            ?: g2.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        root.addView(g2, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        // График — только если есть хотя бы две точки с сервера.
        val hourly = server?.hourly.orEmpty()
        if (hourly.size >= 2) {
            val maxV = hourly.maxOf { it.second }.coerceAtLeast(1)
            val chart = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); background = rounded(WHITE, 16, LINE) }
            chart.addView(TextView(this).apply { text = "Выручка по часам"; textSize = 13f; weight(Fonts.STRONG); setTextColor(INK); setPadding(0, 0, 0, dp(10)) })
            val bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            hourly.forEach { (h, v) ->
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                col.addView(View(this).apply { background = rounded(BRAND, 4, null) }, LinearLayout.LayoutParams(MATCH, dp((6 + 64.0 * v / maxV).toInt())))
                col.addView(TextView(this).apply { text = "%02d".format(h); textSize = 10f; gravity = Gravity.CENTER; setTextColor(UiKit.MUTED); setPadding(0, dp(3), 0, 0) })
                bars.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
            chart.addView(bars, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(chart, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
        }
        val top = server?.top.orEmpty()
        if (top.isNotEmpty()) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); background = rounded(WHITE, 16, LINE) }
            card.addView(TextView(this).apply { text = "Топ за смену"; textSize = 13f; weight(Fonts.STRONG); setTextColor(INK); setPadding(0, 0, 0, dp(6)) })
            top.take(3).forEach { (n, q) ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, dp(5)) }
                r.addView(TextView(this).apply { text = n; textSize = 12.5f; setTextColor(INK) }, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(TextView(this).apply { text = q.toString(); textSize = 12.5f; weight(Fonts.STRONG); setTextColor(INK) })
                card.addView(r)
            }
            root.addView(card)
        }
        if (summaryRemote.state == "error") root.addView(TextView(this).apply {
            text = "Сервер недоступен — показаны только чеки этого терминала."; textSize = 10.5f; setTextColor(UiKit.MUTED); setPadding(0, dp(8), 0, dp(4))
        })
        root.addView(btn("Обновить", "ghost") { ensureLoaded(summaryRemote, "summary", true, { it.receipts == null && it.revenueRub == null }, { ShiftApi.summary() }); safeRender() }, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(10) })
    }

    private fun renderShiftBreaks() {
        root.addView(shiftLocalBack("Назад к смене") { shiftView = "home" }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(10) })
        ensureLoaded(breakRemote, "break", isEmpty = { it == null }, fetch = { ShiftApi.breakRecommendation() })
        val rec = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); background = rounded(WHITE, 16, LINE) }
        rec.addView(TextView(this).apply { text = "РЕКОМЕНДАЦИЯ"; textSize = 10f; weight(Fonts.SEMIBOLD); setTextColor(UiKit.MUTED); letterSpacing = 0.08f })
        val r = breakRemote.data
        when {
            breakRemote.state == "ready" && r != null -> {
                rec.addView(TextView(this).apply { text = "${r.from}–${r.to}"; textSize = 22f; weight(Fonts.BOLD); setTextColor(INK); setPadding(0, dp(6), 0, 0) })
                r.reason?.let { why -> rec.addView(TextView(this).apply { text = why; textSize = 12f; setTextColor(UiKit.MUTED); setPadding(0, dp(6), 0, 0) }) }
            }
            breakRemote.state == "loading" || breakRemote.state == "idle" ->
                rec.addView(TextView(this).apply { text = "Загрузка…"; textSize = 14f; setTextColor(UiKit.MUTED); setPadding(0, dp(6), 0, 0) })
            breakRemote.state == "error" ->
                rec.addView(TextView(this).apply { text = "Не удалось загрузить: ${breakRemote.error ?: "нет связи"}"; textSize = 13f; setTextColor(INK); setPadding(0, dp(6), 0, 0) })
            else ->
                rec.addView(TextView(this).apply { text = "Рекомендаций пока нет"; textSize = 15f; weight(Fonts.STRONG); setTextColor(INK); setPadding(0, dp(6), 0, 0) })
        }
        root.addView(rec, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
        if (breakRemote.state != "loading") root.addView(btn("Обновить", "ghost") {
            ensureLoaded(breakRemote, "break", true, { it == null }, { ShiftApi.breakRecommendation() }); safeRender()
        }, LinearLayout.LayoutParams(MATCH, dp(44)))
        root.addView(TextView(this).apply {
            text = "Окно перерыва рассчитывает сервер по загрузке точки. Фиксация перерывов на кассе не ведётся."
            textSize = 10.5f; setTextColor(UiKit.MUTED); setPadding(0, dp(12), 0, 0)
        })
    }

    /**
     * Закрытие смены: только напоминание, фискальное закрытие — штатный Эвотор.
     */
    private fun renderShiftClose() {
        root.addView(shiftLocalBack("Назад к смене") { shiftView = "home" }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(10) })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = rounded(WHITE, 16, LINE)
        }
        card.addView(TextView(this).apply {
            text = "Закрытие смены"
            textSize = 18f
            setOnest(true)
            setTextColor(INK)
        })
        card.addView(TextView(this).apply {
            text = "Закрытие смены проводит Эвотор: будет снят и напечатан Z-отчёт. После закрытия приём заказов недоступен до следующего открытия."
            textSize = 12.5f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(10), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "Перед закрытием проверьте: чеки отправлены, наличные сверены, перерыв завершён."
            textSize = 12f
            setTextColor(UiKit.INK_2)
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })

        root.addView(TextView(this).apply {
            text = "Закрыть смену (Z-отчёт)"
            textSize = 13f
            setOnest(true)
            gravity = Gravity.CENTER
            setTextColor(WHITE)
            background = rounded(BRAND, 14, null)
            isClickable = true
            setOnClickListener {
                if (!shiftBusy) {
                    shiftBusy = true
                    ru.sixthcup.evotor.shift.EvotorShift.closeShiftZ(this@MainActivity) { e ->
                        shiftBusy = false
                        toastMsg(e ?: "Смена закрыта, Z-отчёт напечатан")
                        loadShiftInfo()
                    }
                }
            }
        }, LinearLayout.LayoutParams(MATCH, dp(52)))
    }

    private fun renderShiftStub(view: String) {
        val title = when (view) {
            "ops" -> "Операции"
            "stats" -> "Моя статистика"
            "team" -> "Команда"
            "timesheet" -> "Табель"
            "breaks" -> "Перерывы"
            "close" -> "Закрытие смены"
            else -> view
        }
        val note = when (view) {
            "ops" -> "Внесение / изъятие / X-отчёт — через штатные команды Эвотора. UI-форма и вызовы — следующие блоки."
            "stats" -> "Нужен API: выручка и число чеков за смену сотрудника. Без API цифры не показываем."
            "team" -> "Read-only список кто на смене. Источник — бэкенд staff/shift (ещё не подключён)."
            "timesheet" -> "Календарь месяца + статусы дней. Read-only на кассе; правки в админке. API — следующий блок."
            "breaks" -> "Рекомендации и фиксация перерывов — с бэкенда."
            "close" -> "Закрытие фискальной смены — только штатный экран Эвотора. Здесь будет подготовка/напоминание."
            else -> ""
        }
        root.addView(shiftLocalBack("Назад к смене") { shiftView = "home" }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(10) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(WHITE, 16, LINE)
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setOnest(true)
            setTextColor(INK)
        })
        card.addView(TextView(this).apply {
            text = note
            textSize = 12.5f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(8), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "Экран-заглушка · дизайн index сохранён в плане блоков 12+"
            textSize = 10.5f
            setTextColor(UiKit.INK_2)
            setPadding(0, dp(12), 0, 0)
        })
        root.addView(card)
    }

    // ── Очередь бариста (локально на терминале) ──

    private val queueHandler = Handler(Looper.getMainLooper())
    private val queueTick = object : Runnable {
        override fun run() {
            if (screen == Screen.QUEUE) { safeRender(); queueHandler.postDelayed(this, 30_000L) }
        }
    }
    private fun startQueueTicker() { queueHandler.removeCallbacks(queueTick); queueHandler.postDelayed(queueTick, 30_000L) }
    private fun stopQueueTicker() { queueHandler.removeCallbacks(queueTick) }

    private fun hhmm(t: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))

    private fun agoText(t: Long): String {
        val min = ((System.currentTimeMillis() - t) / 60_000L).toInt().coerceAtLeast(0)
        return if (min < 1) "только что" else if (min < 60) "$min мин назад" else "${min / 60} ч назад"
    }

    /** Компактный индикатор в шапке: часы + номера «готовится», галочка + номера «готов». Пусто → нет индикатора. */
    private fun queueIndicator(): View? {
        val all = try { OrderQueueStore.load(this) } catch (_: Throwable) { emptyList() }
        val prep = QueueOps.preparing(all, OrderQueueStore.showPending(this))
        val ready = QueueOps.ready(all)
        if (prep.isEmpty() && ready.isEmpty()) return null
        fun nums(l: List<QueueOrder>): String {
            val shown = l.take(3).joinToString(" ") { it.label }
            return if (l.size > 3) "$shown +${l.size - 3}" else shown
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(11), 0)
            background = rounded(WHITE, 999, LINE)
            isClickable = true
            isFocusable = true
            contentDescription = "Очередь заказов"
            setOnClickListener { screen = Screen.QUEUE; safeRender() }
        }
        fun part(res: Int, color: Int, text: String) {
            box.addView(icon(this, res, 14f, color), LinearLayout.LayoutParams(dp(14), dp(14)))
            box.addView(TextView(this).apply {
                this.text = text; textSize = 11f; weight(Fonts.STRONG); setTextColor(color); maxLines = 1
                setPadding(dp(4), 0, dp(8), 0)
            })
        }
        if (prep.isNotEmpty()) part(R.drawable.ic_clock, INK, nums(prep))
        if (ready.isNotEmpty()) part(R.drawable.ic_check, UiKit.SUCCESS, nums(ready))
        return box
    }

    /**
     * Post-pay: что бариста/гость видит сразу после закрытия чека — номер заказа и состояние карты.
     * Фискальный пайплайн Эвотора не участвует: только наш экран.
     */
    private fun renderPostPay() {
        topBar.removeAllViews()
        topBar.setPadding(dp(12), dp(10), dp(12), dp(8))
        val order = postPayOrder
        root.addView(TextView(this).apply {
            text = "ЧЕК ЗАКРЫТ"
            textSize = 10f
            letterSpacing = 0.16f
            weight(Fonts.SEMIBOLD)
            setTextColor(UiKit.MUTED)
        })
        root.addView(TextView(this).apply {
            text = order?.let { "Заказ № ${it.label}" } ?: "Заказ принят"
            textSize = 32f
            weight(Fonts.STRONG)
            letterSpacing = -0.02f
            setTextColor(INK)
            setPadding(0, dp(6), 0, 0)
        })
        if (order != null) {
            root.addView(TextView(this).apply {
                val items = order.items.joinToString(", ")
                text = if (items.length > 90) items.take(90) + "…" else items
                textSize = 11.5f
                setTextColor(UiKit.INK_2)
                setPadding(0, dp(6), 0, 0)
            })
            root.addView(TextView(this).apply {
                text = rub(order.totalRub)
                textSize = 10.5f
                setTextColor(UiKit.MUTED)
                setPadding(0, dp(4), 0, 0)
            })
        }

        val card = CardSession.get(this)?.takeIf { it.code.isNotEmpty() }
        if (card != null) {
            root.addView(buildCartLoyalty(card), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
            val p = CardProgress.of(card)
            if (p.giftAvailable || p.cashbackKopecks > 0) {
                root.addView(TextView(this).apply {
                    text = when {
                        p.giftAvailable && p.kind == "code" -> "Подарок и бонусы останутся на карте: списание — по QR."
                        p.giftAvailable -> "Подарок остался на карте — покажите QR на следующей покупке."
                        else -> "Бонусы на карте: ${p.cashbackKopecks / 100} ₽."
                    }
                    textSize = 10.5f
                    setTextColor(UiKit.MUTED)
                    setPadding(0, dp(8), 0, 0)
                })
            }
        } else {
            root.addView(TextView(this).apply {
                text = "Карта не привязана — чашки и бонусы не начислены."
                textSize = 10.5f
                setTextColor(UiKit.MUTED)
                setPadding(0, dp(14), 0, 0)
            })
        }

        root.addView(button("К баристе", primary = true, heightDp = 48) {
            postPayOrder = null
            screen = Screen.QUEUE
            safeRender()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
        root.addView(TextView(this).apply {
            text = "Новый заказ"
            textSize = 12f
            weight(Fonts.SEMIBOLD)
            gravity = Gravity.CENTER
            setTextColor(INK)
            isClickable = true
            isFocusable = true
            setPadding(0, dp(14), 0, dp(8))
            setOnClickListener {
                postPayOrder = null
                screen = Screen.ORDER
                safeRender()
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun renderQueue() {
        startQueueTicker()
        topBar.setPadding(dp(12), dp(10), dp(12), dp(8))
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(closeButtonBack("К заказам") { screen = Screen.ORDER; safeRender() })
        val all = try { OrderQueueStore.load(this) } catch (_: Throwable) { emptyList() }
        val prep = QueueOps.preparing(all, OrderQueueStore.showPending(this))
        val ready = QueueOps.ready(all)
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(TextView(this).apply {
            text = "Очередь"; textSize = 16f; weight(Fonts.STRONG); setTextColor(INK)
        })
        topBar.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        fun section(title: String, count: Int) {
            root.addView(TextView(this).apply {
                text = "$title · $count"; textSize = 10f; letterSpacing = 0.1f; weight(Fonts.SEMIBOLD); setTextColor(UiKit.MUTED)
                setPadding(dp(2), dp(12), 0, dp(8))
            })
        }
        fun orderRow(o: QueueOrder, isReady: Boolean) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(WHITE, 14, LINE)
            }
            row.addView(TextView(this).apply {
                text = o.label; textSize = 26f; weight(Fonts.BOLD); gravity = Gravity.CENTER
                setTextColor(
                    when {
                        isReady -> UiKit.SUCCESS
                        o.isPending -> UiKit.MUTED
                        else -> WHITE
                    },
                )
                background = rounded(if (isReady) UiKit.SUCCESS_SOFT else if (o.isPending) UiKit.SURFACE_2 else INK, 12, null)
            }, LinearLayout.LayoutParams(dp(64), dp(56)).apply { rightMargin = dp(12) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = o.items.joinToString(", ").ifBlank { "Заказ" }
                textSize = 12.5f; weight(Fonts.SEMIBOLD); setTextColor(INK); maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(this).apply {
                text = when {
                    isReady -> "Готов в ${hhmm(o.readyAt ?: o.createdAt)} · скроется через ${QueueOps.READY_TTL_MS / 60_000L} мин"
                    o.isPending -> "Ждёт оплаты · ${hhmm(o.createdAt)} (номер после чека)"
                    else -> "${hhmm(o.createdAt)} · ${agoText(o.createdAt)}"
                }
                textSize = 10f; setTextColor(UiKit.MUTED); setPadding(0, dp(3), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (!o.isPending) {
                val action = if (isReady) btn("В работу", "ghost") {
                    OrderQueueStore.reopen(this@MainActivity, o.number); safeRender()
                } else btn("Готово", "primary") {
                    OrderQueueStore.markReady(this@MainActivity, o.number); safeRender()
                }
                row.addView(action, LinearLayout.LayoutParams(WRAP, dp(48)).apply { leftMargin = dp(10) })
            }
            row.addView(TextView(this).apply {
                text = "✕"; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(UiKit.MUTED); contentDescription = "Убрать из очереди"
                isClickable = true; isFocusable = true
                setPadding(dp(8), 0, dp(4), 0)
                setOnClickListener {
                    if (o.isPending) OrderQueueStore.removeById(this@MainActivity, o.id)
                    else OrderQueueStore.remove(this@MainActivity, o.number)
                    safeRender()
                }
            }, LinearLayout.LayoutParams(WRAP, dp(44)))
            root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }

        section("В РАБОТЕ", prep.size)
        if (prep.isEmpty()) root.addView(TextView(this).apply {
            text = "Нет заказов в работе. Номер появится после подтверждения оплаты."
            textSize = 12f; setTextColor(UiKit.MUTED); setPadding(dp(2), 0, 0, dp(8))
        })
        prep.forEach { orderRow(it, false) }
        if (ready.isNotEmpty()) {
            section("ГОТОВЫ", ready.size)
            ready.forEach { orderRow(it, true) }
        }
        root.addView(TextView(this).apply {
            text = "Очередь хранится на этом терминале. Готовые заказы скрываются через ${QueueOps.READY_TTL_MS / 60_000L} мин."
            textSize = 10f; setTextColor(UiKit.MUTED); setPadding(dp(2), dp(8), 0, 0)
        })
    }

    // ───────────────────────── view-хелперы ─────────────────────────

    /**
     * `.sheet`: скругление сверху 24, скрим rgba(23,20,15,.42), на 7″/10″ — колонка ≤ 520/560 по центру.
     * Контент листа сам задаёт белый фон; подложку BottomSheet делаем прозрачной, чтобы углы были круглыми.
     */
    private fun styleSheet(dialog: BottomSheetDialog) {
        val r = dp(24).toFloat()
        dialog.window?.setDimAmount(0.42f)
        val m = Ds.mode(this)
        if (m != Ds.Mode.M5) {
            val w = dp(if (m == Ds.Mode.M10) 560 else 520)
            dialog.window?.setLayout(w, ViewGroup.LayoutParams.MATCH_PARENT)
            dialog.window?.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        }
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            sheet.background = GradientDrawable().apply { setColor(WHITE); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            sheet.clipToOutline = true
        }
    }

    /** Иконка из res/drawable с цветом и размером в dp — для compound drawables. */
    private fun tinted(res: Int, color: Int, sizeDp: Int): android.graphics.drawable.Drawable {
        val d = androidx.core.content.ContextCompat.getDrawable(this, res)!!.mutate()
        androidx.core.graphics.drawable.DrawableCompat.setTint(d, color)
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        return d
    }

    /** `.sheet-close`: круг 44, фон surface-2, иконка ✕ 15. */
    private fun closeButton(desc: String, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = rounded(UiKit.SURFACE_2, 22, null)
        isClickable = true
        isFocusable = true
        contentDescription = desc
        addView(icon(this@MainActivity, R.drawable.ic_close, 15f, UiKit.INK_2), FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    /** Фон с 1-dp линией сверху (`border-top: 1px solid var(--line)`). */
    private fun topBorder(color: Int): android.graphics.drawable.Drawable {
        val line = android.graphics.drawable.ColorDrawable(LINE)
        val fill = android.graphics.drawable.ColorDrawable(color)
        return android.graphics.drawable.LayerDrawable(arrayOf(line, fill)).apply { setLayerInset(1, 0, dp(1), 0, 0) }
    }

    private fun text(t: String, sp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) setOnest(true)
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
        if (::root.isInitialized) loadShiftInfo()
        startCardPoll()
        try { val f = IntentFilter(ScannerReceiver.ACTION_INTERNAL_SCAN); if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, f, RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(scanReceiver, f) } catch (_: Throwable) {}
        // Вернулись с экрана оплаты: показываем явное подтверждение результата (состояние хранится на диске).
        if (::root.isInitialized && PendingPayment.active(this)) {
            // Вернулись с экрана оплаты Эвотор.
            val sellOpen = try {
                ReceiptApi.getReceipt(this, Receipt.Type.SELL) != null
            } catch (_: Throwable) {
                null
            }
            when (sellOpen) {
                false -> {
                    // Чек закрыт (оплата завершена или чек аннулирован после оплаты) → очередь, корзина чистая.
                    val done = confirmPendingOrder()
                    Cart.clear()
                    // Чек закрыт: выбор льгот больше не нужен (чистим здесь, а не в сервисе скидки).
                    CheckoutChoice.clear(this)
                    PendingPayment.clear(this)
                    // Номер и состояние карты показываем сразу после оплаты (post-pay).
                    postPayOrder = done
                    screen = if (done != null) Screen.POSTPAY else Screen.QUEUE
                    // Чашки появятся у гостя только после poll на backend — тянем свежие данные.
                    refreshCardAsync()
                    safeRender()
                }
                true -> {
                    // Чек продажи ещё открыт — оплата отменена/не завершена, корзину оставляем.
                    cancelPendingOrder()
                    CheckoutChoice.clear(this)
                    PendingPayment.clear(this)
                    screen = Screen.ORDER
                    safeRender()
                }
                null -> {
                    // Не удалось проверить — не теряем корзину, уходим в заказ. PENDING снимет TTL.
                    PendingPayment.clear(this)
                    screen = Screen.ORDER
                    safeRender()
                }
            }
        }
        // Каталог/цены не должны «залёживаться» на кассе, которая днями не закрывает приложение.
        // Фоновое обновление не чаще раза в 60 с (п.6 контракта) — гейт внутри loadProducts.
        if (::root.isInitialized) loadProducts(userInitiated = false)
    }

    override fun onPause() {
        // Экран не виден — сервер больше не опрашиваем (опрос возобновится в onResume).
        stopCardPoll()
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.ORDER -> { notice = null; screen = Screen.CARD; safeRender() }
            Screen.SHIFT -> { screen = Screen.ORDER; safeRender() }
            Screen.POSTPAY -> { postPayOrder = null; screen = Screen.QUEUE; safeRender() }
            Screen.QUEUE -> { screen = Screen.ORDER; safeRender() }
            Screen.CARD -> super.onBackPressed()
        }
    }

    private companion object {
        const val TAG = "SixthCup"
        const val GROUP_COMBOS = "@combos"
        /** Фоновое обновление каталога — не чаще раза в минуту (п.6 контракта). */
        const val CATALOG_REFRESH_MS = 60 * 1000L
        /** Лимит «можно несколько» для добавок из номенклатуры Эвотор (у них нет maxCount с backend). */
        const val EVOTOR_EXTRA_LIMIT = 99
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

