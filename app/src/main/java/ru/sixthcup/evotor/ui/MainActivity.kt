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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import ru.sixthcup.evotor.R
import ru.sixthcup.evotor.data.CardInfo
import ru.sixthcup.evotor.data.CardSession
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
    /** empty | loading | error — без демо-смен. */
    private var tsLoadState: String = "empty"
    /** Пожелания: выбранные дни (локальный черновик до PUT API). */
    private val wishSelected = sortedSetOf<Int>()
    /** open | draft | sent_local — без серверных accepted/declined. */
    private var wishStatus: String = "open"
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
    private var cartDialog: BottomSheetDialog? = null
    private var checkoutDialog: BottomSheetDialog? = null

    private val handler = Handler(Looper.getMainLooper())
    private var qrRunnable: Runnable? = null

    private enum class Screen { CARD, ORDER, PAYMENT, SHIFT }
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

    private fun setProducts(list: List<CatalogProduct>) {
        products = list
        variantModel = DrinkVariants.build(list)
        combos = Combos.from(list)
    }

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
                if (known != null) {
                    lastEnrich = known
                    upsellDefault = known.upsellDefault
                }
                if (first == null) { if (products.isEmpty()) productsError = true } else setProducts(first)
                CheckoutChoice.saveCombo(this, comboDiscountRub())
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
                variantModel = DrinkVariants.build(merged)
                combos = Combos.from(merged)
                upsellDefault = enrich.upsellDefault
                val withTop = merged.count { it.toppings.isNotEmpty() }
                val withSellable = merged.count { it.toppings.any { top -> !top.evotorUuid.isNullOrBlank() } }
                Log.i(TAG, "enrich ok products=${merged.size} withToppings=$withTop sellableToppings=$withSellable upsellDefault=${enrich.upsellDefault.size}")
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
        when (screen) {
            Screen.CARD -> renderCard()
            Screen.ORDER -> renderOrder()
            Screen.PAYMENT -> renderPayment()
            Screen.SHIFT -> renderShift()
        }
        topBar.visibility = if (topBar.childCount == 0) View.GONE else View.VISIBLE
        bottomBar.visibility = if (bottomBar.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun dismissSheets() {
        try { cartDialog?.dismiss() } catch (_: Throwable) {}
        try { checkoutDialog?.dismiss() } catch (_: Throwable) {}
        cartDialog = null; checkoutDialog = null
    }

    // ── Экран 1: карта (guest-sheet 1:1 index) ──

    private fun renderCard() {
        val card = CardSession.get(this)?.takeIf { it.code.isNotEmpty() }
        // head like guest-sheet
        topBar.setPadding(dp(16), dp(14), dp(12), dp(8))
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "Карта гостя"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        titleCol.addView(TextView(this).apply {
            text = "Лояльность 6.7 · чашки и бонусы"
            textSize = 9.5f
            setTextColor(UiKit.MUTED)
        })
        head.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(TextView(this).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(UiKit.INK_2)
            background = rounded(UiKit.SURFACE_2, 22, null)
            isClickable = true
            contentDescription = "Закрыть"
            setOnClickListener {
                notice = null
                screen = Screen.ORDER
                safeRender()
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        if (resolving) {
            root.addView(banner("Проверяем карту…", Kind.INFO), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        notice?.let {
            root.addView(banner(it, noticeKind), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }

        if (card != null) {
            // guest-card (brand panel)
            val gc = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(15), dp(15), dp(15))
                background = rounded(BRAND, 18, null)
            }
            gc.addView(TextView(this).apply {
                text = "6.7 CLUB · КАРТА ГОСТЯ"
                textSize = 8f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0x9EFFFFFF.toInt())
                letterSpacing = 0.16f
            })
            val displayName = card.cardCode.ifBlank { "Гость" }
            gc.addView(TextView(this).apply {
                text = displayName
                textSize = 21f
                setTypeface(null, Typeface.BOLD)
                setTextColor(WHITE)
                setPadding(0, dp(4), 0, 0)
            })
            if (card.freeStatus == "OFFLINE") {
                gc.addView(TextView(this).apply {
                    text = "Нет связи · баланс неизвестен"
                    textSize = 10f
                    setTextColor(0xADFFFFFF.toInt())
                })
            }
            val stats = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(18), 0, 0)
            }
            fun stat(label: String, value: String) {
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply {
                    text = label
                    textSize = 8f
                    setTextColor(0x8CFFFFFF.toInt())
                    letterSpacing = 0.08f
                })
                col.addView(TextView(this).apply {
                    text = value
                    textSize = 19f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(WHITE)
                    setPadding(0, dp(2), 0, 0)
                })
                stats.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            stat("ЧАШКИ", "${card.paidCups} / ${card.cupsForFree}")
            stat("КЭШБЭК", "${card.cashbackKopecks / 100} ₽")
            gc.addView(stats)
            // progress dots (paidCups of cupsForFree)
            val goal = card.cupsForFree.coerceAtLeast(1)
            val filled = card.paidCups.coerceIn(0, goal)
            val dots = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(14), 0, 0)
            }
            for (i in 0 until goal.coerceAtMost(12)) {
                dots.addView(View(this).apply {
                    background = rounded(if (i < filled) WHITE else 0x33FFFFFF.toInt(), 4, null)
                }, LinearLayout.LayoutParams(0, dp(7), 1f).apply {
                    if (i > 0) leftMargin = dp(4)
                })
            }
            gc.addView(dots)
            gc.addView(TextView(this).apply {
                text = when {
                    card.freeAvailable > 0 -> "Бесплатный напиток доступен в этом чеке"
                    else -> {
                        val left = (goal - filled).coerceAtLeast(0)
                        "Ещё $left до бесплатного напитка"
                    }
                }
                textSize = 9f
                setTextColor(0xB3FFFFFF.toInt())
                setPadding(0, dp(7), 0, 0)
            })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(14), 0, 0)
            }
            actions.addView(TextView(this).apply {
                text = "К заказу"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(INK)
                background = rounded(0xFFF6F3EE.toInt(), 9, null)
                isClickable = true
                setOnClickListener { notice = null; screen = Screen.ORDER; safeRender() }
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(6) })
            actions.addView(TextView(this).apply {
                text = "Отвязать"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(WHITE)
                background = rounded(0x24FFFFFF.toInt(), 9, null)
                isClickable = true
                setOnClickListener {
                    CardSession.clear(this@MainActivity)
                    notice = null
                    safeRender()
                }
            }, LinearLayout.LayoutParams(0, dp(36), 1f))
            gc.addView(actions)
            root.addView(gc, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

            root.addView(TextView(this).apply {
                text = "Карта одна для кассы и PWA: чашки, бонусы и подарки считаются на сервере после SELL."
                textSize = 10.5f
                setTextColor(UiKit.MUTED)
            })
            return
        }

        // empty: qr-zone + phone/number field
        val qrZone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
            background = rounded(WHITE, 16, LINE)
        }
        qrZone.addView(TextView(this).apply {
            text = "▣"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(UiKit.MUTED)
            setPadding(0, 0, 0, dp(8))
        })
        qrZone.addView(TextView(this).apply {
            text = "Поднесите карту гостя"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            gravity = Gravity.CENTER
        })
        qrZone.addView(TextView(this).apply {
            text = "QR в PWA 6.7 Coffee · или введите номер"
            textSize = 11f
            setTextColor(UiKit.MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(qrZone, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = "или по номеру"
            textSize = 11f
            setTextColor(UiKit.MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(WHITE, 14, LINE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val field = EditText(this).apply {
            hint = "Номер карты или QR"
            inputType = InputType.TYPE_CLASS_TEXT
            setRawInputType(InputType.TYPE_CLASS_NUMBER)
            isSingleLine = true
            textSize = 15f
            setTextColor(INK)
            setHintTextColor(UiKit.MUTED)
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            isEnabled = !resolving
            background = null
            setPadding(dp(12), 0, dp(8), 0)
            minHeight = dp(48)
        }
        input = field
        row.addView(field, LinearLayout.LayoutParams(0, dp(48), 1f))
        val go = TextView(this).apply {
            text = "→"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(WHITE)
            background = rounded(BRAND, 12, null)
            isClickable = true
            isEnabled = !resolving
            setOnClickListener { resolve(field.text.toString()) }
        }
        row.addView(go, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        val last = lastCardCode
        if (last != null && !resolving) {
            root.addView(TextView(this).apply {
                text = "Последняя: ${lastCardLabel ?: last.take(12)}"
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(INK)
                background = rounded(WHITE, 12, LINE)
                setPadding(dp(12), dp(14), dp(12), dp(14))
                isClickable = true
                setOnClickListener { resolve(last) }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }

        root.addView(TextView(this).apply {
            text = "Карта гостя одна для кассы и PWA: чашки, бонусы и подарки считаются одинаково."
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(4), 0, 0)
        })

        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                resolve(field.text.toString()); true
            } else false
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

        bottomBar.setPadding(dp(12), dp(6), dp(12), dp(10))
        bottomBar.addView(TextView(this).apply {
            text = "Продолжить без карты"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(INK)
            background = rounded(WHITE, 14, LINE)
            isClickable = true
            setOnClickListener {
                CardSession.clear(this@MainActivity)
                notice = null
                screen = Screen.ORDER
                safeRender()
            }
        }, LinearLayout.LayoutParams(MATCH, dp(52)))

        if (!resolving) field.post {
            if (!isFinishing) {
                field.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
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

        // pos-head 1:1 с index.html: lockup (logo + brand + shift) | guest-chip
        topBar.setPadding(dp(14), dp(14), dp(14), dp(8))
        topBar.addView(buildPosHead(card), LinearLayout.LayoutParams(MATCH, WRAP))
        notice?.let { topBar.addView(banner(it, noticeKind, 14f).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) }) }

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val catalogColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        content.addView(catalogColumn, LinearLayout.LayoutParams(0, MATCH, 1f))

        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        strip.addView(chips)
        chipsScroll = strip; chipsRow = chips
        catalogColumn.addView(strip, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

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
                // У комбо плюс/минус не трогаем: набор удаляется целиком из чека.
                if (combos.none { it.id == prod.uuid }) {
                    Cart.decProduct(prod.uuid)
                    onCartChanged()
                }
            },
        ))
        adapter = ad
        val screenWidth = resources.configuration.screenWidthDp
        // Две колонки на 5″, три на 7″/10″: крупнее, чем в 4-колоночном варианте, но мельче исходных.
        val columns = if (screenWidth >= 520) 3 else 2
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

        // cartbar 1:1 index (.cartbar / .empty-cartbar)
        bottomBar.setBackgroundColor(PAGE)
        bottomBar.setPadding(dp(11), dp(2), dp(11), dp(4))
        bottomBar.elevation = 0f
        bottomBar.addView(buildCartBar(card), LinearLayout.LayoutParams(MATCH, WRAP))
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
        cartSidebarTotal?.text = "Итого  ${rub(cartTotalRub())}"
    }

    /** Поиск/чипы (если набор групп изменился) + строки списка. */
    private fun refreshChromeAndRows(toTop: Boolean) {
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

    /** cats + .chip 1:1 index: height 38, pill, selected = ink (не brand). */
    private fun rebuildChips() {
        val row = chipsRow ?: return
        row.removeAllViews(); chipViews.clear()
        if (group == GROUP_COMBOS && combos.isEmpty()) group = null
        if (group != null && group != GROUP_COMBOS && group !in groupsCache) group = null
        fun add(label: String, value: String?) {
            val tv = TextView(this).apply {
                text = label
                textSize = 11.5f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (group != value) { group = value; updateChipStyles(); refreshRows(true) }
                }
            }
            row.addView(tv, LinearLayout.LayoutParams(WRAP, dp(38)).apply { rightMargin = dp(6) })
            chipViews.add(value to tv)
        }
        add("Всё", null)
        if (combos.isNotEmpty()) add("Комбо", GROUP_COMBOS)
        groupsCache.forEach { add(it, it) }
        updateChipStyles()
        chipsScroll?.visibility =
            if (groupsCache.isEmpty() && combos.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateChipStyles() {
        for ((value, tv) in chipViews) {
            val on = group == value
            // index .chip.on → background: var(--ink); color: #fff
            tv.setTextColor(if (on) WHITE else UiKit.INK_2)
            tv.setTypeface(null, Typeface.NORMAL)
            tv.background = rounded(if (on) INK else WHITE, 999, if (on) INK else LINE)
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
        val tiles = variantModel.tiles
        val q = ""
        // Комбо показываются плитками вместе с меню или отдельной категорией — как в index.
        val comboTiles = if (combos.isNotEmpty() && q.isEmpty() && (group == null || group == GROUP_COMBOS))
            combos.map { Combos.tile(it, products) } else emptyList()
        val filtered = if (group == GROUP_COMBOS) emptyList() else tiles.filter {
            (group == null || it.groupName == group) && (q.isEmpty() || it.name.contains(q, ignoreCase = true))
        }
        if (filtered.isEmpty() && comboTiles.isEmpty()) {
            val msg = if (q.isNotEmpty()) "Ничего не найдено по «$q»" else "В этой категории пока нет позиций"
            return listOf(CatalogRow.State("nf", msg, false, null))
        }
        val rows = ArrayList<CatalogRow>(filtered.size + comboTiles.size + 4)
        if (group == null && q.isEmpty()) {
            val focus = filtered.filter { it.featured }.take(8)
            if (focus.isNotEmpty()) {
                rows.add(CatalogRow.Header("Сейчас берут"))
                rows.add(CatalogRow.Focus(focus))
                rows.add(CatalogRow.Header("Всё меню"))
            }
        }
        if (comboTiles.isNotEmpty()) {
            if (group == GROUP_COMBOS) rows.add(CatalogRow.Header("Комбо"))
            comboTiles.forEach { rows.add(CatalogRow.Item(it)) }
        }
        filtered.forEach { rows.add(CatalogRow.Item(it)) }
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
    private fun optionsFor(p: CatalogProduct): OptionPicker.Opts {
        val evotor = variantModel.options
        if (!evotor.isEmpty) {
            fun map(list: List<CatalogProduct>) = list.map {
                OptionPicker.Opt(it.uuid, it.optionLabel, it.priceRub, it.uuid, it.defaultVariant)
            }
            return OptionPicker.Opts(
                milk = map(evotor.milk),
                syrup = map(evotor.syrup),
                sugar = map(evotor.sugar),
                extra = map(evotor.extra),
            )
        }
        val byGroup = p.toppings.groupBy { it.groupKey.lowercase() }
        fun toOpts(key: String) = byGroup[key].orEmpty().map {
            OptionPicker.Opt("t:${it.id}", it.name, it.priceRub, it.evotorUuid?.takeIf { u -> u.isNotBlank() })
        }
        return OptionPicker.Opts(
            milk = toOpts("milk"),
            syrup = toOpts("syrup"),
            sugar = toOpts("sugar"),
            extra = toOpts("topping") + toOpts("extra") + toOpts("other"),
        )
    }

    /**
     * item-sheet 1:1 index: hero → название+цена → описание → теги → «в чеке уже N» →
     * опции → qty + «В чек · сумма».
     */
    private fun showItemSheet(p: CatalogProduct) {
        dismissSheets()
        val variants = variantModel.variantsOf(p)
        val opts = if (p.supportsMods) optionsFor(p) else OptionPicker.Opts()

        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WHITE)
        }
        root.addView(buildItemHero(p) { dialog.dismiss() }, LinearLayout.LayoutParams(MATCH, dp(150)))

        val scroll = NestedScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val priceView = TextView(this).apply {
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        titleRow.addView(TextView(this).apply {
            text = p.name
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        titleRow.addView(priceView)
        body.addView(titleRow)

        p.recipe?.takeIf { it.isNotBlank() }?.let { desc ->
            body.addView(TextView(this).apply {
                text = desc
                textSize = 10.5f
                setTextColor(UiKit.MUTED)
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(4), 0, 0)
            })
        }
        val tags = itemTags(p)
        if (tags.childCount > 0) body.addView(tags)

        val existing = Cart.all()
            .filter { it.productUuid in variants.map { v -> v.uuid } }
            .fold(BigDecimal.ZERO) { acc, l -> acc.add(l.quantity) }
        if (existing.signum() > 0) {
            body.addView(TextView(this).apply {
                text = "В чеке уже $existing порц. Новая сохранится отдельной позицией."
                textSize = 10f
                setTextColor(UiKit.INK_2)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(UiKit.SURFACE_2, 12, LINE)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }

        val addBtn = TextView(this).apply {
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            background = rounded(BRAND, 14, null)
            isClickable = true
            isFocusable = true
        }
        val qtyLabel = TextView(this).apply {
            text = "1"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(INK)
            minWidth = dp(28)
        }
        var qty = 1

        val picker = OptionPicker(this)
        picker.bind(variants, opts, p)
        body.addView(picker, LinearLayout.LayoutParams(MATCH, WRAP))

        fun paint() {
            priceView.text = rub(picker.unitPrice())
            addBtn.text = "В чек · ${rub(picker.unitPrice().multiply(BigDecimal(qty)))}"
            qtyLabel.text = qty.toString()
        }
        picker.onChange = { paint() }
        paint()

        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(WHITE)
            elevation = 4f * resources.displayMetrics.density
        }
        val qtyWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(WHITE, 12, LINE)
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
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        qtyWrap.addView(qtyLabel, LinearLayout.LayoutParams(WRAP, dp(48)))
        qtyWrap.addView(qBtn("+") {
            if (qty < 99) { qty += 1; paint() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        foot.addView(qtyWrap, LinearLayout.LayoutParams(WRAP, dp(48)).apply { rightMargin = dp(10) })
        foot.addView(addBtn, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        addBtn.setOnClickListener {
            val variant = picker.selectedVariant ?: return@setOnClickListener
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
                    Cart.add(CartLine(uuid, "${o.label} (${variant.name})", o.price, BigDecimal.ONE))
                }
            }
            onCartChanged()
            dialog.dismiss()
        }

        dialog.setContentView(root)
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
        val opts = if (drink.supportsMods) optionsFor(drink) else OptionPicker.Opts()

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
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        pics.addView(ThumbView(this).apply { setCorner(14); bind(drink.name, drink.imageUrl, 360) },
            LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(10) })
        pics.addView(TextView(this).apply {
            text = "+"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(UiKit.MUTED)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(24), WRAP))
        pics.addView(ThumbView(this).apply { setCorner(14); bind(food.name, food.imageUrl, 360) },
            LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = dp(10) })
        hero.addView(pics, FrameLayout.LayoutParams(MATCH, MATCH))
        hero.addView(TextView(this).apply {
            text = "КОМБО"
            textSize = 7f
            setTypeface(null, Typeface.BOLD)
            setTextColor(UiKit.INK_2)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = rounded(0xEBFFFFFF.toInt(), 5, null)
        }, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(12); leftMargin = dp(16)
        })
        hero.addView(TextView(this).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(UiKit.INK_2)
            background = rounded(0xE6FFFFFF.toInt(), 22, null)
            isClickable = true
            contentDescription = "Закрыть"
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(10); rightMargin = dp(12)
        })
        root.addView(hero, LinearLayout.LayoutParams(MATCH, dp(150)))

        val scroll = NestedScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val priceView = TextView(this).apply {
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        titleRow.addView(TextView(this).apply {
            text = combo.name
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
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

        // выгода
        val oldSum = combo.drinkBasePriceRub.add(combo.foodPriceRub)
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(UiKit.SUCCESS_SOFT, 12, null)
            addView(TextView(this@MainActivity).apply {
                text = "Выгода"
                textSize = 10f
                setTextColor(UiKit.SUCCESS)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "−" + rub(combo.discountRub)
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(UiKit.SUCCESS)
            })
            addView(TextView(this@MainActivity).apply {
                text = rub(oldSum)
                textSize = 10f
                setTextColor(UiKit.MUTED)
                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        // состав
        val compHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(7))
        }
        compHeader.addView(TextView(this).apply {
            text = "Состав комбо"
            textSize = 9f
            setTypeface(null, Typeface.BOLD)
            setTextColor(UiKit.INK_2)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        compHeader.addView(TextView(this).apply {
            text = "2 позиции в чеке"
            textSize = 8.5f
            setTextColor(UiKit.MUTED)
        })
        body.addView(compHeader)

        fun compRow(p: CatalogProduct, detail: String?, price: BigDecimal) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(ThumbView(this).apply { setCorner(8); bind(p.name, p.imageUrl, 200) },
                LinearLayout.LayoutParams(dp(34), dp(34)))
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), 0, dp(6), 0)
            }
            col.addView(TextView(this).apply {
                text = p.name
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(INK)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(this).apply {
                text = detail.orEmpty()
                textSize = 9f
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
            body.addView(row)
        }

        val foodPrice = combo.foodPriceRub
        compRow(food, null, foodPrice)

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
        body.addView(drinkRowHost, LinearLayout.LayoutParams(MATCH, WRAP))
        fun paintDrinkRow() {
            drinkRowHost.removeAllViews()
            val v = picker.selectedVariant ?: drink
            val detail = picker.modsText().ifBlank { null }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(ThumbView(this).apply { setCorner(8); bind(v.name, v.imageUrl, 200) },
                LinearLayout.LayoutParams(dp(34), dp(34)))
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), 0, dp(6), 0)
            }
            col.addView(TextView(this).apply {
                text = v.name
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(INK)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (detail != null) col.addView(TextView(this).apply {
                text = detail
                textSize = 9f
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
        body.addView(picker, LinearLayout.LayoutParams(MATCH, WRAP))

        val addBtn = TextView(this).apply {
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            background = rounded(BRAND, 14, null)
            isClickable = true
            isFocusable = true
        }
        fun paint() {
            val total = picker.unitPrice().add(foodPrice)
            priceView.text = rub(total.subtract(combo.discountRub))
            addBtn.text = (if (editingGroupId != null) "Сохранить · " else "В заказ · ") +
                rub(total.subtract(combo.discountRub))
        }
        picker.onChange = { paintDrinkRow(); paint() }
        paint()

        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(WHITE)
            elevation = 4f * resources.displayMetrics.density
        }
        foot.addView(addBtn, LinearLayout.LayoutParams(MATCH, dp(50)))
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        addBtn.setOnClickListener {
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
                    ),
                )
            }
            onCartChanged()
            dialog.dismiss()
        }

        dialog.setContentView(root)
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
        hero.addView(ThumbView(this).apply { setCorner(0); bind(p.name, p.imageUrl, 480) }, FrameLayout.LayoutParams(MATCH, MATCH))
        val badge = when {
            p.isCombo -> "КОМБО"
            p.featured -> "ХИТ"
            else -> null
        }
        if (badge != null) {
            hero.addView(TextView(this).apply {
                text = badge
                textSize = 7f
                setTypeface(null, Typeface.BOLD)
                setTextColor(UiKit.INK_2)
                setPadding(dp(6), dp(3), dp(6), dp(3))
                background = rounded(0xEBFFFFFF.toInt(), 5, null)
            }, FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(12); leftMargin = dp(16)
            })
        }
        hero.addView(TextView(this).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(UiKit.INK_2)
            background = rounded(0xE6FFFFFF.toInt(), 22, null)
            isClickable = true
            contentDescription = "Закрыть"
            setOnClickListener { onClose() }
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
            setPadding(0, dp(8), 0, 0)
        }
        listOfNotNull(
            p.groupName?.takeIf { it.isNotBlank() },
            if (p.freeEligible) "6.7" else null,
        ).forEach { t ->
            tags.addView(TextView(this).apply {
                text = t.uppercase()
                textSize = 8f
                setTypeface(null, Typeface.BOLD)
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
     * cartbar index: dark bar height 66, count circle, sum, CTA brand «К оплате».
     * Тап по count/sum → showCart; CTA → showUpsellThenCheckout (существующий pay-flow).
     * empty-cartbar: без «Повторить» — прошлого заказа в APK нет (не выдумываем).
     */
    private fun buildCartBar(card: CardInfo?): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // empty
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            minimumHeight = dp(62)
            background = GradientDrawable().apply {
                setColor(0x8CFFFFFF.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), UiKit.LINE_STRONG)
            }
        }
        val emptyText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emptyText.addView(TextView(this).apply {
            text = "Чек пуст"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        emptyText.addView(TextView(this).apply {
            text = "Начните с хитов"
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
        })
        empty.addView(emptyText, LinearLayout.LayoutParams(0, WRAP, 1f))
        val repeat = TextView(this).apply {
            text = "Повторить"
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BRAND)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(WHITE, 12, LINE)
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
        empty.addView(repeat, LinearLayout.LayoutParams(WRAP, dp(38)).apply { leftMargin = dp(8) })
        empty.setOnClickListener { /* index: open cart overlay even if empty */ showCart() }
        emptyCartBarRoot = empty
        wrap.addView(empty, LinearLayout.LayoutParams(MATCH, dp(62)))

        // filled cartbar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(8), 0)
            minimumHeight = dp(66)
            background = GradientDrawable().apply {
                setColor(0xFF171410.toInt())
                cornerRadius = dp(20).toFloat()
            }
            elevation = 8f * resources.displayMetrics.density
        }
        val count = TextView(this).apply {
            text = "0"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFF171410.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF6F3EE.toInt())
            }
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
            textSize = 9f
            setTextColor(0x9EF6F3EE.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        cartSubView = sub
        sumCol.addView(sub)
        val total = TextView(this).apply {
            text = "0 ₽"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(WHITE)
            maxLines = 1
        }
        cartTotalView = total
        sumCol.addView(total, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        bar.addView(sumCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        val cta = TextView(this).apply {
            text = "К оплате  →"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(BRAND)
                cornerRadius = dp(14).toFloat()
            }
            isClickable = true
            isFocusable = true
            contentDescription = "К оплате"
            setOnClickListener { showUpsellThenCheckout(card) }
        }
        bar.addView(cta, LinearLayout.LayoutParams(WRAP, dp(48)))
        bar.tag = cta
        cartBarRoot = bar
        wrap.addView(bar, LinearLayout.LayoutParams(MATCH, dp(66)))
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
            setImageResource(R.drawable.ic_logo_67)
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
            setTypeface(null, Typeface.BOLD)
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
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(UiKit.SUCCESS)
            }
        }
        shift.addView(dot, LinearLayout.LayoutParams(dp(5), dp(5)).apply { rightMargin = dp(4); gravity = Gravity.CENTER_VERTICAL })
        shift.addView(TextView(this).apply {
            text = "Смена открыта"
            textSize = 8f
            setTextColor(UiKit.MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        shift.setOnClickListener {
            shiftView = "home"
            notice = null
            screen = Screen.SHIFT
            safeRender()
        }
        brandCol.addView(shift, LinearLayout.LayoutParams(WRAP, WRAP))
        lockup.addView(brandCol, LinearLayout.LayoutParams(WRAP, WRAP))

        head.addView(lockup, LinearLayout.LayoutParams(0, WRAP, 1f))
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
            elevation = 2f * resources.displayMetrics.density
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
                else -> "${card.paidCups} из ${card.cupsForFree} чашек"
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
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        meta.addView(TextView(this).apply {
            text = cups
            textSize = 8.5f
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
     * order-type, cart-list rows, note, foot (Итого + К оплате).
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
            textSize = 7f
            setTypeface(null, Typeface.BOLD)
            setTextColor(UiKit.WHITE)
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background = rounded(BRAND, 4, null)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        badgeRow.addView(TextView(this).apply {
            text = combo?.name ?: "Комбо"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        titleCol.addView(badgeRow)

        if (combo != null && combo.discountRub.signum() > 0) {
            titleCol.addView(TextView(this).apply {
                text = "выгода ${rub(combo.discountRub)} · ${rub(componentSum)}"
                textSize = 9f
                setTextColor(UiKit.MUTED)
                setPadding(0, dp(2), 0, 0)
            })
        }
        head.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(TextView(this).apply {
            text = rub(price)
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        head.addView(TextView(this).apply {
            text = "✕"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(UiKit.MUTED)
            isClickable = true
            contentDescription = "Удалить комбо"
            setOnClickListener {
                Cart.removeComboGroup(groupId)
                onCartChanged()
                showCart()
            }
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(4) })
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
                textSize = 8.5f
                setTextColor(UiKit.MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(TextView(this).apply {
                text = rub(l.lineTotal())
                textSize = 9.5f
                setTextColor(UiKit.MUTED)
            })
            card.addView(row)
        }

        if (combo != null) {
            card.addView(TextView(this).apply {
                text = "Изменить опции"
                textSize = 9.5f
                setTypeface(null, Typeface.BOLD)
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
     * Список бонусов/подарка остаётся на экране «К оплате»: там же решается резерв.
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
                setTypeface(null, Typeface.BOLD)
                setTextColor(INK)
            })
            col.addView(TextView(this).apply {
                text = "Чашки и бонусы спишутся в этом чеке"
                textSize = 8.5f
                setTextColor(UiKit.MUTED)
            })
            box.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            box.addView(TextView(this).apply {
                text = "Сканировать"
                textSize = 9.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(UiKit.INK_2)
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                background = rounded(WHITE, 8, LINE)
            }, LinearLayout.LayoutParams(WRAP, dp(32)))
            return box
        }

        val goal = card.cupsForFree.coerceAtLeast(1)
        val cups = minOf(card.paidCups, goal)
        val left = (goal - cups).coerceAtLeast(0)

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
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFF6F3EE.toInt())
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (card.cashbackKopecks > 0) {
            top.addView(TextView(this).apply {
                text = "${card.cashbackKopecks / 100} бонусов"
                textSize = 9.5f
                setTextColor(0xB3F6F3EE.toInt())
            })
        }
        box.addView(top)

        box.addView(TextView(this).apply {
            text = if (card.freeAvailable > 0) "Подарок доступен" else "$cups из $goal чашек"
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFF6F3EE.toInt())
            setPadding(0, dp(9), 0, 0)
        })
        box.addView(TextView(this).apply {
            text = when {
                card.freeStatus == "OFFLINE" -> "Нет связи · бонусы недоступны"
                card.freeAvailable > 0 -> "1 бесплатный напиток"
                else -> "$left до подарка"
            }
            textSize = 8.5f
            setTextColor(0xB3F6F3EE.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        box.addView(
            UiKit.loyaltyDots(this, cups, goal, onColor = 0xFFF6F3EE.toInt()),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) },
        )
        return box
    }

    private fun showCart() {
        dismissSheets()
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WHITE)
        }
        // grip
        root.addView(View(this).apply {
            background = rounded(UiKit.LINE_STRONG, 2, null)
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8); bottomMargin = dp(4)
        })

        // sheet-head
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(8))
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = "Чек"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        }
        val countBadge = TextView(this).apply {
            textSize = 9f
            setTypeface(null, Typeface.BOLD)
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
            textSize = 9.5f
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
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(UiKit.INK_2)
            background = rounded(UiKit.SURFACE_2, 22, null)
            isClickable = true
            contentDescription = "Закрыть чек"
            setOnClickListener { dialog.dismiss() }
        }
        head.addView(closeBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) })
        root.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroll = NestedScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // sheet-foot
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(WHITE)
            elevation = 4f * resources.displayMetrics.density
        }
        val footPriceCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        footPriceCol.addView(TextView(this).apply {
            text = "Итого"
            textSize = 9f
            setTextColor(UiKit.MUTED)
        })
        val footTotal = TextView(this).apply {
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        }
        footPriceCol.addView(footTotal)
        foot.addView(footPriceCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        val goPay = TextView(this).apply {
            text = "К оплате  →"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(BRAND, 14, null)
            isClickable = true
            isFocusable = true
        }
        foot.addView(goPay, LinearLayout.LayoutParams(WRAP, dp(50)))
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        fun paintOrderType(row: LinearLayout) {
            row.removeAllViews()
            listOf("С собой", "Здесь").forEach { label ->
                val on = orderType == label
                val b = TextView(this).apply {
                    text = label
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
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
            val lines = Cart.all()
            val n = cartLineCount(lines)
            countBadge.text = n.toString()
            countBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
            subTv.text = "Чек · ${orderType.lowercase()}"
            clearBtn.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            foot.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            footTotal.text = rub(cartTotalRub())
            goPay.setOnClickListener {
                dialog.dismiss()
                val card = CardSession.get(this)?.takeIf { it.code.isNotEmpty() }
                showUpsellThenCheckout(card)
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
                    setTypeface(null, Typeface.BOLD)
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
                // thumb placeholder
                val thumb = FrameLayout(this).apply {
                    background = GradientDrawable().apply {
                        setColor(0xFFF3EEE5.toInt())
                        cornerRadius = dp(12).toFloat()
                    }
                }
                thumb.addView(TextView(this).apply {
                    text = line.name.take(1).uppercase()
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(UiKit.INK_2)
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(MATCH, MATCH))
                row.addView(thumb, LinearLayout.LayoutParams(dp(46), dp(46)))

                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, dp(4), 0)
                }
                info.addView(TextView(this).apply {
                    text = line.name
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
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
                    setTypeface(null, Typeface.BOLD)
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
                    setTypeface(null, Typeface.BOLD)
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

                val remove = TextView(this).apply {
                    text = "×"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(UiKit.MUTED)
                    contentDescription = "Удалить: ${line.name}"
                    isClickable = true
                    setOnClickListener {
                        Cart.remove(key)
                        onCartChanged()
                        fill()
                    }
                }
                row.addView(remove, LinearLayout.LayoutParams(dp(32), dp(32)))
                body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
                body.addView(View(this).apply { setBackgroundColor(0xFFF1EBE1.toInt()) }, LinearLayout.LayoutParams(MATCH, dp(1)))
            }

            // upsell from backend default (existing data only)
            val upsell = upsellDefault.filter { u ->
                val uuid = u.evotorUuid?.takeIf { it.isNotBlank() }
                    ?: products.firstOrNull { it.name.equals(u.name, true) }?.uuid
                uuid != null && Cart.qty(uuid) == 0
            }.take(6)
            if (upsell.isNotEmpty()) {
                body.addView(TextView(this).apply {
                    text = "ДОБАВИТЬ К ЗАКАЗУ"
                    textSize = 9.5f
                    setTypeface(null, Typeface.BOLD)
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
        dialog.setContentView(root)
        dialog.setOnDismissListener { onCartChanged(); if (cartDialog === dialog) cartDialog = null }
        cartDialog = dialog
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    // ── Экран 3: «К оплате» с лояльностью ──


    /** Есть ли в чеке еда — тогда рекомендации не нужны (index: !hasFood()). */
    private fun hasFoodInCart(): Boolean {
        val byUuid = products.associateBy { it.uuid }
        return Cart.all().any { line ->
            val p = byUuid[line.productUuid] ?: return@any false
            !p.supportsMods && !p.freeEligible
        }
    }

    private fun showUpsellThenCheckout(card: CardInfo?) {
        if (recShown || hasFoodInCart()) {
            checkout(card)
            return
        }
        val inCart = Cart.all().map { it.productUuid }.toSet()
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
                candidates[id] = u.copy(evotorUuid = id)
            }
        }
        upsellDefault.forEach { u ->
            val id = resolveUuid(u) ?: return@forEach
            candidates.putIfAbsent(id, u.copy(evotorUuid = id))
        }
        val list = candidates.entries.take(3)
        if (list.isEmpty()) {
            checkout(card)
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
        root.addView(View(this).apply {
            background = rounded(UiKit.LINE_STRONG, 2, null)
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8); bottomMargin = dp(4)
        })
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(8))
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "Что-то к напитку?"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        titleCol.addView(TextView(this).apply {
            text = "Выпечка к кофе · можно пропустить"
            textSize = 9.5f
            setTextColor(UiKit.MUTED)
        })
        head.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(TextView(this).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(UiKit.INK_2)
            background = rounded(UiKit.SURFACE_2, 22, null)
            isClickable = true
            setOnClickListener { dialog.dismiss(); checkout(card) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(head)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        body.addView(TextView(this).apply {
            text = "Короткая подборка. Добавьте 0, 1 или несколько — или сразу к оплате."
            textSize = 12f
            setTextColor(UiKit.MUTED)
            setPadding(0, 0, 0, dp(10))
        })

        val payCta = TextView(this).apply {
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            background = rounded(BRAND, 14, null)
            isClickable = true
        }
        fun paintPay() {
            payCta.text = "К оплате · ${rub(cartTotalRub())}"
        }
        paintPay()

        list.forEach { (uuid, u) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(WHITE, 14, LINE)
            }
            row.addView(ThumbView(this).apply {
                setCorner(10)
                bind(u.name, u.imageUrl ?: products.firstOrNull { it.uuid == uuid }?.imageUrl, 160)
            }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(10) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = u.name
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(INK)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            products.firstOrNull { it.uuid == uuid }?.recipe?.takeIf { it.isNotBlank() }?.let { meta ->
                info.addView(TextView(this).apply {
                    text = meta
                    textSize = 9.5f
                    setTextColor(UiKit.MUTED)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            info.addView(TextView(this).apply {
                text = rub(u.priceRub)
                textSize = 12f
                setTextColor(UiKit.INK_2)
            })
            row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
            val qtyTv = TextView(this).apply {
                text = Cart.qty(uuid).toString()
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(INK)
                minWidth = dp(28)
            }
            fun step(label: String, act: () -> Unit) = TextView(this).apply {
                text = label
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(UiKit.INK_2)
                background = rounded(UiKit.SURFACE_2, 12, LINE)
                isClickable = true
                setOnClickListener {
                    act()
                    onCartChanged()
                    qtyTv.text = Cart.qty(uuid).toString()
                    paintPay()
                }
            }
            row.addView(step("−") { Cart.decProduct(uuid) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(qtyTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4); rightMargin = dp(4) })
            row.addView(step("+") {
                Cart.add(CartLine(uuid, u.name, u.priceRub, BigDecimal.ONE, null, false))
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        root.addView(NestedScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(WHITE)
        }
        val skip = TextView(this).apply {
            text = "Пропустить"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(INK)
            background = rounded(WHITE, 14, LINE)
            isClickable = true
            setOnClickListener { dialog.dismiss(); checkout(card) }
        }
        foot.addView(skip, LinearLayout.LayoutParams(dp(120), dp(50)).apply { rightMargin = dp(10) })
        payCta.setOnClickListener { dialog.dismiss(); checkout(card) }
        foot.addView(payCta, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        dialog.setContentView(root)
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    /**
     * pay-sheet: loyalty choices + «Оплатить на Эвоторе».
     * Способы card/cash из index НЕ эмулируем — оплату принимает штатный экран Эвотора (SellLauncher).
     */
    private fun checkout(card: CardInfo?) {
        if (Cart.isEmpty()) {
            notice = "Корзина пуста — добавьте позиции."
            noticeKind = Kind.WARN
            safeRender()
            return
        }
        notice = null
        if (card == null) {
            pay(false, false, 0L)
            return
        }
        val now = System.currentTimeMillis() / 1000
        val reserved = DiscountCalc.reserved(card, now)
        val eligibleLines = Cart.all().filter { it.freeEligible }
        val eligiblePrices = eligibleLines.map { it.priceRub }
        val giftPossible = card.freeAvailable > 0 && eligiblePrices.isNotEmpty()
        val cbPossible = card.cashbackKopecks > 0 && card.reservationId != null
        if (!giftPossible && !cbPossible) {
            pay(false, false, 0L)
            return
        }

        dismissSheets()
        val dialog = BottomSheetDialog(this)
        dialog.setCanceledOnTouchOutside(false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WHITE)
        }
        root.addView(View(this).apply {
            background = rounded(UiKit.LINE_STRONG, 2, null)
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8); bottomMargin = dp(4)
        })
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(8))
        }
        val hCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hCol.addView(TextView(this).apply {
            text = "Оплата"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        hCol.addView(TextView(this).apply {
            text = "Чек · ${orderType.lowercase()} · Эвотор"
            textSize = 9.5f
            setTextColor(UiKit.MUTED)
        })
        head.addView(hCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(TextView(this).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(UiKit.INK_2)
            background = rounded(UiKit.SURFACE_2, 22, null)
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(head)

        val scroll = NestedScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // guest row
        val guestRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(UiKit.SURFACE_2, 14, LINE)
        }
        val gLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gLeft.addView(TextView(this).apply {
            text = "Гость"
            textSize = 9f
            setTextColor(UiKit.MUTED)
        })
        gLeft.addView(TextView(this).apply {
            text = card.cardCode.ifBlank { "по QR" }
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        guestRow.addView(gLeft, LinearLayout.LayoutParams(0, WRAP, 1f))
        val gRight = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        gRight.addView(TextView(this).apply {
            text = "Стаканы"
            textSize = 9f
            setTextColor(UiKit.MUTED)
            gravity = Gravity.END
        })
        gRight.addView(TextView(this).apply {
            text = "${card.paidCups} из ${card.cupsForFree}"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            gravity = Gravity.END
        })
        guestRow.addView(gRight)
        box.addView(guestRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        val expiry = card.reservationExpiresAt
        val resText = when {
            card.reservationId == null -> null
            expiry == 0L -> null
            expiry - now > 0 -> "Бонусы зарезервированы ещё ~${(expiry - now + 59) / 60} мин."
            reserved -> "Резерв истёк: бонус применится, только если его не занял другой чек."
            else -> "Резерв истёк — бонус не применится. Отсканируйте карту заново."
        }
        resText?.let {
            box.addView(banner(it, if (reserved) Kind.INFO else Kind.WARN).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
            })
        }
        if ((giftPossible || cbPossible) && !reserved) {
            box.addView(banner("Бонус по карте недоступен: резерв не создан или истёк. Оплата пройдёт без скидки.", Kind.WARN).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
            })
        }

        var useGift = giftPossible && reserved
        var useCb = false
        val cheapestName = eligibleLines.minByOrNull { it.priceRub }?.name
        val cheapest = DiscountCalc.cheapestEligible(eligiblePrices) ?: BigDecimal.ZERO
        val totals = TextView(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            setPadding(0, dp(8), 0, dp(8))
        }
        lateinit var recompute: () -> Unit
        fun choice(a: String, b: String, onA: (Boolean) -> Unit) {
            val g = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val ra = RadioButton(this).apply {
                id = View.generateViewId(); text = a; textSize = 14f; setTextColor(INK); minHeight = dp(48)
            }
            val rb = RadioButton(this).apply {
                id = View.generateViewId(); text = b; textSize = 14f; setTextColor(INK); minHeight = dp(48)
            }
            g.addView(ra); g.addView(rb)
            g.check(ra.id)
            g.setOnCheckedChangeListener { _, id -> onA(id == ra.id); recompute() }
            box.addView(g, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        }
        if (giftPossible && reserved) {
            choice("Применить подарок: «${cheapestName ?: ""}» −${rub(cheapest)}", "Без подарка") { useGift = it }
        } else useGift = false
        if (cbPossible && reserved) {
            choice("Копить кэшбэк", "Списать кэшбэк (до ${card.cashbackKopecks / 100} ₽)") { useCb = !it }
        }
        box.addView(totals)
        recompute = {
            val gross = Cart.gross()
            val r = DiscountCalc.compute(
                gross, eligiblePrices, reserved, useGift, card.freeAvailable,
                if (useCb) card.cashbackKopecks else 0L, card.cashbackKopecks,
            )
            val combo = comboDiscountRub()
            val dueNow = gross.subtract(r.total).subtract(combo).max(BigDecimal.ZERO)
            totals.text = "Итого ${rub(gross)}" +
                (if (combo.signum() > 0) "\nКомбо −${rub(combo)}" else "") +
                (if (r.free.signum() > 0) "\nПодарок −${rub(r.free)}" else "") +
                (if (r.cashback.signum() > 0) "\nКэшбэк −${rub(r.cashback)}" else "") +
                "\nК оплате: ${rub(dueNow)}"
        }
        recompute()

        box.addView(TextView(this).apply {
            text = "Эвотор примет оплату, применит скидку и напечатает фискальный чек."
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(8), 0, dp(4))
        })

        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(WHITE)
        }
        foot.addView(TextView(this).apply {
            text = "Оплатить на Эвоторе"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            background = rounded(BRAND, 14, null)
            isClickable = true
            setOnClickListener {
                dialog.dismiss()
                pay(useGift && reserved, useCb && reserved, if (useCb && reserved) card.cashbackKopecks else 0L)
            }
        }, LinearLayout.LayoutParams(MATCH, dp(52)))
        foot.addView(TextView(this).apply {
            text = "Назад к заказу"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(UiKit.MUTED)
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(6) })
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        dialog.setContentView(root)
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
        // Выгода комбо должна быть уже записана: её применяет DiscountIntegrationService.
        CheckoutChoice.saveCombo(this, comboDiscountRub())
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

    // ── Экран SHIFT: хаб смены (index shift-page) ──

    /**
     * Block 11: оболочка «Смена» 1:1 по структуре index.
     * Цифры выручки/чеков НЕ рисуем из воздуха — пока нет API смены.
     * Подэкраны timesheet/ops/… — заглушки до блоков 12+.
     */
    private fun renderShift() {
        topBar.setPadding(dp(12), dp(10), dp(12), dp(8))
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "← К заказам"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BRAND)
            isClickable = true
            setPadding(0, dp(8), dp(12), dp(8))
            setOnClickListener {
                shiftView = "home"
                notice = null
                screen = Screen.ORDER
                safeRender()
            }
        })
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "Смена"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
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
                else -> "Хаб · 6.7"
            }
            textSize = 9.5f
            setTextColor(UiKit.MUTED)
        })
        head.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(UiKit.SUCCESS)
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
        top.addView(TextView(this).apply {
            text = "●  Смена на терминале"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(UiKit.SUCCESS)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(TextView(this).apply {
            text = "Эвотор"
            textSize = 10f
            setTextColor(UiKit.MUTED)
        })
        hero.addView(top)
        hero.addView(TextView(this).apply {
            text = "Фискальная смена ведётся в Эвоторе. Здесь — статус, табель и подготовка операций."
            textSize = 12f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(10), 0, 0)
        })
        hero.addView(TextView(this).apply {
            text = "Выручка и чеки появятся после API смены (бэкенд)."
            textSize = 11f
            setTextColor(UiKit.INK_2)
            setPadding(0, dp(8), 0, 0)
        })
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
                setTypeface(null, Typeface.BOLD)
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
            text = "Закрыть смену →"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
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
            setTypeface(null, Typeface.BOLD)
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

    /**
     * Block 12: табель — календарь 1:1 index (ts-monthbar, ts-week, ts-day).
     * Смены сотрудников НЕ выдумываем: tsLoadState=empty до GET /api/devices/timesheet.
     * Пожелания → shiftView=wishes (блок 13).
     */
    private fun renderShiftTimesheet() {
        root.addView(TextView(this).apply {
            text = "← Назад к смене"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BRAND)
            setPadding(0, 0, 0, dp(10))
            isClickable = true
            setOnClickListener { shiftView = "home"; safeRender() }
        })

        // store line
        val store = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        store.addView(TextView(this).apply {
            text = "⌂"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(UiKit.INK_2)
            background = rounded(WHITE, 8, LINE)
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(9) })
        val storeCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        storeCol.addView(TextView(this).apply {
            text = "6.7 Coffee"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        storeCol.addView(TextView(this).apply {
            text = "Ваша кофейня · без переключения"
            textSize = 10f
            setTextColor(UiKit.MUTED)
        })
        store.addView(storeCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(store)

        when (tsLoadState) {
            "loading" -> {
                root.addView(TextView(this).apply {
                    text = "Загрузка табеля…"
                    textSize = 13f
                    setTextColor(UiKit.MUTED)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(24))
                })
                return
            }
            "error" -> {
                val err = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    background = rounded(WHITE, 14, LINE)
                }
                err.addView(TextView(this).apply {
                    text = "Не удалось загрузить табель"
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(INK)
                })
                err.addView(TextView(this).apply {
                    text = "Проверьте подключение. Приложение не подменяет данные выдуманными."
                    textSize = 12f
                    setTextColor(UiKit.MUTED)
                    setPadding(0, dp(6), 0, dp(12))
                })
                err.addView(TextView(this).apply {
                    text = "Повторить"
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(WHITE)
                    background = rounded(BRAND, 12, null)
                    isClickable = true
                    // API ещё нет — остаёмся в empty после «попытки»
                    setOnClickListener { tsLoadState = "empty"; safeRender() }
                }, LinearLayout.LayoutParams(MATCH, dp(48)))
                root.addView(err)
                return
            }
        }

        // banner empty-honest
        root.addView(TextView(this).apply {
            text = "График смен появится после подключения API табеля. Календарь уже готов."
            textSize = 11f
            setTextColor(0xFF15613D.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(UiKit.SUCCESS_SOFT, 9, null)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })

        // month bar
        val monthNames = arrayOf(
            "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
        )
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun navBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(UiKit.INK_2)
            background = rounded(WHITE, 9, LINE)
            isClickable = true
            setOnClickListener { onClick() }
        }
        bar.addView(navBtn("‹") {
            if (tsMonth == 0) { tsMonth = 11; tsYear -= 1 } else tsMonth -= 1
            tsSelectedDay = 1
            safeRender()
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        mid.addView(TextView(this).apply {
            text = "${monthNames[tsMonth]} $tsYear"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            gravity = Gravity.CENTER
        })
        mid.addView(TextView(this).apply {
            text = "только просмотр"
            textSize = 9f
            setTextColor(UiKit.MUTED)
            gravity = Gravity.CENTER
        })
        bar.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(navBtn("›") {
            if (tsMonth == 11) { tsMonth = 0; tsYear += 1 } else tsMonth += 1
            tsSelectedDay = 1
            safeRender()
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

        // weekday headers Пн…Вс
        val wd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { d ->
            wd.addView(TextView(this).apply {
                text = d
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(UiKit.MUTED)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(wd, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })

        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, tsYear)
            set(java.util.Calendar.MONTH, tsMonth)
            set(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        // Calendar: SUNDAY=1 … Saturday=7 → Monday-first offset
        val firstDow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val lead = (firstDow + 5) % 7 // Mon=0
        val today = java.util.Calendar.getInstance()
        val isThisMonth = today.get(java.util.Calendar.YEAR) == tsYear &&
            today.get(java.util.Calendar.MONTH) == tsMonth
        val todayDay = today.get(java.util.Calendar.DAY_OF_MONTH)

        if (tsSelectedDay > daysInMonth) tsSelectedDay = daysInMonth

        var day = 1
        var cell = 0
        val totalCells = lead + daysInMonth
        val rows = (totalCells + 6) / 7
        repeat(rows) {
            val week = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) {
                if (cell < lead || day > daysInMonth) {
                    week.addView(View(this), LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                } else {
                    val d = day
                    val selected = d == tsSelectedDay
                    val isToday = isThisMonth && d == todayDay
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
                        setOnClickListener {
                            tsSelectedDay = d
                            safeRender()
                        }
                    }
                    cellV.addView(TextView(this).apply {
                        text = d.toString()
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setTextColor(when {
                            selected -> WHITE
                            else -> INK
                        })
                    })
                    cellV.addView(TextView(this).apply {
                        text = "—"
                        textSize = 8f
                        gravity = Gravity.CENTER
                        setTextColor(if (selected) 0xB3FFFFFF.toInt() else UiKit.MUTED)
                    })
                    week.addView(cellV, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                    day++
                }
                cell++
            }
            root.addView(week, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        // selected day detail
        root.addView(TextView(this).apply {
            text = "День $tsSelectedDay · ${monthNames[tsMonth].lowercase()}"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            setPadding(0, dp(14), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Смена не загружена — нет данных с сервера."
            textSize = 12f
            setTextColor(UiKit.MUTED)
        })

        // wishes entry
        root.addView(TextView(this).apply {
            text = "Пожелания по выходным →"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(INK)
            background = rounded(WHITE, 14, LINE)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            setOnClickListener {
                shiftView = "wishes"
                safeRender()
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })

        root.addView(TextView(this).apply {
            text = "Табель на кассе только для просмотра. Правки графика — в админке."
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(10), 0, 0)
        })
    }

    /**
     * Block 13: пожелания по выходным (index wishes).
     * Выбор дат локально; лимит/период/accepted — только с API (пока не подключено).
     * «Отправить» не имитирует success от сервера.
     */
    private fun renderShiftWishes() {
        // target month: next calendar month if not set
        if (wishYear == 0) {
            val c = java.util.Calendar.getInstance()
            c.add(java.util.Calendar.MONTH, 1)
            wishYear = c.get(java.util.Calendar.YEAR)
            wishMonth = c.get(java.util.Calendar.MONTH)
        }
        val monthNames = arrayOf(
            "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
        )

        root.addView(TextView(this).apply {
            text = "← Назад к табелю"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BRAND)
            setPadding(0, 0, 0, dp(10))
            isClickable = true
            setOnClickListener { shiftView = "timesheet"; safeRender() }
        })

        // store
        root.addView(TextView(this).apply {
            text = "6.7 Coffee · пожелания — не назначение выходного"
            textSize = 11f
            setTextColor(UiKit.MUTED)
            setPadding(0, 0, 0, dp(10))
        })

        // target + pill
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val hCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hCol.addView(TextView(this).apply {
            text = "Пожелания на"
            textSize = 10f
            setTextColor(UiKit.MUTED)
        })
        hCol.addView(TextView(this).apply {
            text = "${monthNames[wishMonth]} $wishYear"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        head.addView(hCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        val pillLabel = when (wishStatus) {
            "draft" -> "Черновик"
            "sent_local" -> "Локально"
            else -> "Открыто"
        }
        val pillBg = when (wishStatus) {
            "draft" -> UiKit.WARN_SOFT
            "sent_local" -> UiKit.BRAND_SOFT
            else -> UiKit.SUCCESS_SOFT
        }
        val pillFg = when (wishStatus) {
            "draft" -> UiKit.WARN
            "sent_local" -> BRAND
            else -> 0xFF15613D.toInt()
        }
        head.addView(TextView(this).apply {
            text = pillLabel
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(pillFg)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(pillBg, 999, null)
        })
        root.addView(head)

        // rules placeholder (no invented period)
        val rules = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(UiKit.SURFACE_2, 12, LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        fun rule(label: String, value: String) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = label
                textSize = 9f
                setTextColor(UiKit.MUTED)
            })
            col.addView(TextView(this).apply {
                text = value
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(INK)
            })
            rules.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        rule("Период подачи", "с сервера")
        rule("Лимит дат", "с сервера")
        root.addView(rules, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })

        root.addView(TextView(this).apply {
            text = "Выберите даты. Это пожелание, а не самостоятельное назначение выходных. Отправка на сервер — после API."
            textSize = 12f
            setTextColor(UiKit.MUTED)
            setPadding(0, 0, 0, dp(10))
        })

        // weekday headers
        val wd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { d ->
            wd.addView(TextView(this).apply {
                text = d
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(UiKit.MUTED)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(wd, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })

        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, wishYear)
            set(java.util.Calendar.MONTH, wishMonth)
            set(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val firstDow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val lead = (firstDow + 5) % 7
        var day = 1
        var cell = 0
        val total = lead + daysInMonth
        val rows = (total + 6) / 7
        val editable = wishStatus == "open" || wishStatus == "draft"
        repeat(rows) {
            val week = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) {
                if (cell < lead || day > daysInMonth) {
                    week.addView(View(this), LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                } else {
                    val d = day
                    val on = d in wishSelected
                    val cellV = TextView(this).apply {
                        text = d.toString()
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(if (on) WHITE else INK)
                        background = rounded(if (on) INK else WHITE, 10, if (on) INK else LINE)
                        isEnabled = editable
                        alpha = if (editable) 1f else 0.7f
                        isClickable = editable
                        if (editable) {
                            setOnClickListener {
                                if (d in wishSelected) wishSelected.remove(d)
                                else wishSelected.add(d)
                                wishStatus = if (wishSelected.isEmpty()) "open" else "draft"
                                safeRender()
                            }
                        }
                    }
                    week.addView(cellV, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                    day++
                }
                cell++
            }
            root.addView(week, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        // selected summary
        root.addView(TextView(this).apply {
            text = "Выбрано ${wishSelected.size}" + if (wishSelected.isEmpty()) "" else
                ": " + wishSelected.joinToString(", ")
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            setPadding(0, dp(12), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = if (editable) "Черновик на устройстве" else "Только просмотр"
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
        })

        if (editable) {
            root.addView(TextView(this).apply {
                text = "Отправить пожелания"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (wishSelected.isEmpty()) UiKit.MUTED else WHITE)
                background = rounded(if (wishSelected.isEmpty()) UiKit.SURFACE_2 else BRAND, 14, null)
                isEnabled = wishSelected.isNotEmpty()
                isClickable = wishSelected.isNotEmpty()
                setOnClickListener {
                    // API нет: не врём «отправлено на сервер»
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "API пожеланий ещё не подключён. Черновик сохранён на устройстве.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    wishStatus = "sent_local"
                    safeRender()
                }
            }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(14) })
        } else if (wishStatus == "sent_local") {
            root.addView(TextView(this).apply {
                text = "Изменить выбор"
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(INK)
                background = rounded(WHITE, 14, LINE)
                isClickable = true
                setOnClickListener {
                    wishStatus = if (wishSelected.isEmpty()) "open" else "draft"
                    safeRender()
                }
            }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(14) })
        }

        root.addView(TextView(this).apply {
            text = "Ограничения, сроки и результат рассмотрения приходят с бэкенда. Приложение не назначает выходные самостоятельно."
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(12), 0, 0)
        })
    }

    /**
     * Block 14: Команда (index team) — read-only.
     * Без демо-ФИО/выручки. Список появится после GET /api/devices/shift/team.
     */
    private fun renderShiftTeam() {
        root.addView(TextView(this).apply {
            text = "← Назад к смене"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BRAND)
            setPadding(0, 0, 0, dp(10))
            isClickable = true
            setOnClickListener { shiftView = "home"; safeRender() }
        })

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        meta.addView(TextView(this).apply {
            text = "Текущая смена"
            textSize = 11f
            setTextColor(UiKit.MUTED)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        meta.addView(TextView(this).apply {
            text = "0 сотрудников"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        root.addView(meta)

        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = rounded(WHITE, 16, LINE)
        }
        empty.addView(TextView(this).apply {
            text = "Команда не загружена"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            gravity = Gravity.CENTER
        })
        empty.addView(TextView(this).apply {
            text = "Список «кто на смене» придёт с бэкенда. Приложение не подставляет демо-сотрудников."
            textSize = 12f
            setTextColor(UiKit.MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(empty, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        // skeleton row placeholders (visual only, no names)
        repeat(3) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(WHITE, 14, LINE)
                alpha = 0.55f
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(UiKit.SURFACE_2)
                }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(View(this).apply {
                background = rounded(UiKit.SURFACE_2, 4, null)
            }, LinearLayout.LayoutParams(dp(120), dp(10)))
            col.addView(View(this).apply {
                background = rounded(UiKit.SURFACE_2, 4, null)
            }, LinearLayout.LayoutParams(dp(80), dp(8)).apply { topMargin = dp(6) })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }

        root.addView(TextView(this).apply {
            text = "Без рейтингов и сравнений. Данные только за текущую смену."
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(8), 0, 0)
        })
    }

    /**
     * Block 15: Операции (index ops).
     * Фискальные действия выполняет Эвотор. Здесь — список и пояснение, без фейковых сумм «в кассе».
     * Прямой вызов Integration Library (pay in/out) — отдельный блок после проверки API.
     */
    private fun renderShiftOps() {
        root.addView(TextView(this).apply {
            text = "← Назад к смене"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BRAND)
            setPadding(0, 0, 0, dp(10))
            isClickable = true
            setOnClickListener { shiftView = "home"; safeRender() }
        })

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
            setTypeface(null, Typeface.BOLD)
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
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "«${op.title}» проводится в Эвоторе. Интеграция команды — следующий шаг.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = op.title
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(INK)
            })
            col.addView(TextView(this).apply {
                text = op.sub
                textSize = 10.5f
                setTextColor(UiKit.MUTED)
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(TextView(this).apply {
                text = "›"
                textSize = 18f
                setTextColor(UiKit.MUTED)
            })
            root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }

        root.addView(TextView(this).apply {
            text = "Фискальные операции проводит Эвотор. Здесь — подготовка и подтверждение."
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(8), 0, 0)
        })
    }

    /**
     * Block 16: Моя статистика (index stats).
     * Метрики «—» до GET /api/devices/shift/summary. Без демо-графиков и топ-3.
     */
    private fun renderShiftStats() {
        root.addView(TextView(this).apply {
            text = "← Назад к смене"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BRAND)
            setPadding(0, 0, 0, dp(10))
            isClickable = true
            setOnClickListener { shiftView = "home"; safeRender() }
        })

        root.addView(TextView(this).apply {
            text = "Текущая смена · данные с сервера"
            textSize = 11f
            setTextColor(UiKit.MUTED)
            setPadding(0, 0, 0, dp(12))
        })

        fun metric(value: String, label: String): View {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(WHITE, 14, LINE)
            }
            box.addView(TextView(this).apply {
                text = value
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(INK)
            })
            box.addView(TextView(this).apply {
                text = label
                textSize = 10f
                setTextColor(UiKit.MUTED)
                setPadding(0, dp(2), 0, 0)
            })
            return box
        }
        val grid1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        grid1.addView(metric("—", "чека"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
        grid1.addView(metric("—", "выручка"), LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(grid1, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        val grid2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        grid2.addView(metric("—", "средний чек"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
        grid2.addView(metric("—", "напитки / еда"), LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(grid2, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        val chart = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(WHITE, 16, LINE)
        }
        chart.addView(TextView(this).apply {
            text = "Выручка по часам"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        chart.addView(TextView(this).apply {
            text = "График появится после API summary. Демо-столбики не показываем."
            textSize = 11f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(8), 0, dp(8))
        })
        // empty bars visual only
        val bars = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            minimumHeight = dp(72)
        }
        repeat(7) {
            bars.addView(View(this).apply {
                background = rounded(UiKit.SURFACE_2, 4, null)
            }, LinearLayout.LayoutParams(0, dp(12 + it * 4), 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        chart.addView(bars, LinearLayout.LayoutParams(MATCH, dp(80)))
        root.addView(chart, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(WHITE, 16, LINE)
        }
        top.addView(TextView(this).apply {
            text = "Топ-3 за смену"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        top.addView(TextView(this).apply {
            text = "Нет данных — список товаров придёт с бэкенда."
            textSize = 11f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(top)

        root.addView(TextView(this).apply {
            text = "GET /api/devices/shift/summary — чеки, выручка, средний, hourly[], top[]"
            textSize = 10f
            setTextColor(UiKit.INK_2)
            setPadding(0, dp(12), 0, 0)
        })
    }

    /**
     * Block 17: Перерывы (index breaks).
     * Без фейкового «14:30–14:50». Старт/стоп локально не пишем на сервер до API.
     */
    private fun renderShiftBreaks() {
        root.addView(TextView(this).apply {
            text = "← Назад к смене"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BRAND)
            setPadding(0, 0, 0, dp(10))
            isClickable = true
            setOnClickListener { shiftView = "home"; safeRender() }
        })

        val rec = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(WHITE, 16, LINE)
        }
        rec.addView(TextView(this).apply {
            text = "Рекомендация"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(UiKit.MUTED)
            letterSpacing = 0.06f
        })
        rec.addView(TextView(this).apply {
            text = "Нет данных с сервера"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            setPadding(0, dp(6), 0, 0)
        })
        rec.addView(TextView(this).apply {
            text = "Окно перерыва и длительность задаёт бэкенд под загрузку смены."
            textSize = 11f
            setTextColor(UiKit.MUTED)
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(rec, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        val fact = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(UiKit.SURFACE_2, 16, LINE)
        }
        fact.addView(TextView(this).apply {
            text = "Факт перерыва"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(UiKit.MUTED)
        })
        fact.addView(TextView(this).apply {
            text = "Не зафиксирован"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(fact, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })

        root.addView(TextView(this).apply {
            text = "Начать перерыв"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(WHITE)
            background = rounded(BRAND, 14, null)
            isClickable = true
            setOnClickListener {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "API перерывов не подключён. Фиксация на сервере недоступна.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }, LinearLayout.LayoutParams(MATCH, dp(52)))

        root.addView(TextView(this).apply {
            text = "GET/POST /api/devices/shift/breaks — рекомендация и факт."
            textSize = 10f
            setTextColor(UiKit.INK_2)
            setPadding(0, dp(12), 0, 0)
        })
    }

    /**
     * Закрытие смены: только напоминание, фискальное закрытие — штатный Эвотор.
     */
    private fun renderShiftClose() {
        root.addView(TextView(this).apply {
            text = "← Назад к смене"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BRAND)
            setPadding(0, 0, 0, dp(10))
            isClickable = true
            setOnClickListener { shiftView = "home"; safeRender() }
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = rounded(WHITE, 16, LINE)
        }
        card.addView(TextView(this).apply {
            text = "Закрытие смены"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        card.addView(TextView(this).apply {
            text = "Фискальную смену закрывает Эвотор (Z-отчёт / закрытие смены на терминале). 6.7 не дублирует эту операцию."
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
            text = "Открыть меню Эвотор"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(WHITE)
            background = rounded(BRAND, 14, null)
            isClickable = true
            setOnClickListener {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Закройте смену в штатном интерфейсе Эвотора.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
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
        root.addView(TextView(this).apply {
            text = "← Назад к смене"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BRAND)
            setPadding(0, 0, 0, dp(12))
            isClickable = true
            setOnClickListener { shiftView = "home"; safeRender() }
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(WHITE, 16, LINE)
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
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

    // ── Экран 4: ожидание подтверждения оплаты (processing / done visual) ──

    /**
     * После SellLauncher терминал показывает штатную оплату Эвотора.
     * Этот экран — подтверждение результата баристой (как и раньше через PendingPayment).
     * Не эмулируем фискализацию — только UI + те же clear/set.
     */
    private fun renderPayment() {
        val card = CardSession.get(this)?.takeIf { it.code.isNotEmpty() }
        topBar.setPadding(dp(16), dp(14), dp(16), dp(8))
        topBar.addView(TextView(this).apply {
            text = "Оплата · Эвотор"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
        })
        topBar.addView(TextView(this).apply {
            text = "Чек · ${orderType.lowercase()}"
            textSize = 9.5f
            setTextColor(UiKit.MUTED)
        })

        // processing block (centered)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
            background = rounded(WHITE, 20, LINE)
        }
        // static "spinner" ring (no animation dependency)
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(3), BRAND)
                setColor(UiKit.BRAND_SOFT)
            }
        }
        panel.addView(ring, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
        })
        panel.addView(TextView(this).apply {
            text = "Чек открыт на терминале"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            gravity = Gravity.CENTER
        })
        panel.addView(TextView(this).apply {
            text = "После возврата с экрана оплаты Эвотора подтвердите результат ниже."
            textSize = 10.5f
            setTextColor(UiKit.MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), 0)
        })
        panel.addView(TextView(this).apply {
            text = rub(cartTotalRub())
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(INK)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        if (card != null) {
            panel.addView(TextView(this).apply {
                text = "Гость · ${card.cardCode.ifBlank { "QR" }} · ${card.paidCups}/${card.cupsForFree}"
                textSize = 11f
                setTextColor(UiKit.MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
            panel.addView(TextView(this).apply {
                // Чашки начисляет сервер по SELL: не обещаем цифру, которую не можем подтвердить.
                text = "Чашки и бонусы обновятся после синхронизации чека"
                textSize = 9.5f
                setTextColor(UiKit.MUTED)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), 0)
            })
        }
        root.addView(panel, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        // order lines compact
        if (!Cart.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "ПОЗИЦИИ"
                textSize = 9f
                setTypeface(null, Typeface.BOLD)
                setTextColor(UiKit.MUTED)
                letterSpacing = 0.11f
                setPadding(0, 0, 0, dp(6))
            })
            Cart.all().forEach { l ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                row.addView(TextView(this).apply {
                    text = "${l.name} × ${l.quantity.stripTrailingZeros().toPlainString()}"
                    textSize = 12.5f
                    setTextColor(INK)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(TextView(this).apply {
                    text = rub(l.lineTotal())
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(INK)
                })
                root.addView(row)
                root.addView(View(this).apply { setBackgroundColor(0xFFF1EBE1.toInt()) }, LinearLayout.LayoutParams(MATCH, 1))
            }
        }

        bottomBar.setPadding(dp(12), dp(8), dp(12), dp(10))
        bottomBar.addView(TextView(this).apply {
            text = "Чек пробит — новый заказ"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(WHITE)
            background = rounded(BRAND, 14, null)
            isClickable = true
            setOnClickListener {
                // lastCardCode stays in process memory for quick re-use
                CardSession.clear(this@MainActivity)
                Cart.rememberAsLast()
                Cart.clear()
                PendingPayment.clear(this@MainActivity)
                notice = null
                screen = Screen.CARD
                safeRender()
            }
        }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { bottomMargin = dp(8) })
        bottomBar.addView(TextView(this).apply {
            text = "Не оплачено — к заказу"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(INK)
            background = rounded(WHITE, 14, LINE)
            isClickable = true
            setOnClickListener {
                PendingPayment.clear(this@MainActivity)
                notice = null
                screen = Screen.ORDER
                safeRender()
            }
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
            Screen.SHIFT -> { screen = Screen.ORDER; safeRender() }
            Screen.CARD -> super.onBackPressed()
        }
    }

    private companion object {
        const val TAG = "SixthCup"
        const val GROUP_COMBOS = "@combos"
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

