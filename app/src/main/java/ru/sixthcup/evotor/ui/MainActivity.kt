package ru.sixthcup.evotor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.View
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ru.sixthcup.evotor.R
import ru.sixthcup.evotor.data.CardSession
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CartLine
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.InventoryRepository
import ru.sixthcup.evotor.net.LoyaltyApi
import kotlin.concurrent.thread
import ru.sixthcup.evotor.scanner.ScannerReceiver
import ru.sixthcup.evotor.sell.SellLauncher
import java.math.BigDecimal

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var products: List<CatalogProduct> = emptyList()
    private var screen: Screen = Screen.CARD
    private var resolving = false
    private var awaitingPaymentReturn = false
    private var input: EditText? = null
    private var errorMessage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var qrRunnable: Runnable? = null
    private enum class Screen { CARD, ORDER }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = intent.getStringExtra(ScannerReceiver.EXTRA_CODE).orEmpty().trim()
            if (code.isNotEmpty()) {
                if (screen == Screen.CARD || CardSession.get(this@MainActivity) == null) {
                    screen = Screen.CARD
                    safeRender()
                    input?.setText(code)
                    resolve(code)
                } else {
                    Toast.makeText(this@MainActivity, "Сначала сбросьте текущего клиента", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            val frame = findViewById<android.widget.FrameLayout>(R.id.root)
            val scroll = ScrollView(this)
            root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
            scroll.addView(root)
            frame.addView(scroll)
            reloadProducts()
            safeRender()
        } catch (e: Throwable) {
            Toast.makeText(this, "Старт: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun safeRender() { try { render() } catch (e: Throwable) { Toast.makeText(this, "UI: ${e.message}", Toast.LENGTH_LONG).show() } }
    private fun reloadProducts() { products = try { InventoryRepository.load(this) } catch (_: Throwable) { emptyList() } }

    private fun render() {
        root.removeAllViews()
        when (screen) { Screen.CARD -> renderCard(); Screen.ORDER -> renderOrder() }
    }

    private fun renderCard() {
        title("6.7 Coffee")
        body("Карта клиента")
        val field = EditText(this).apply {
            hint = "Введите номер или QR клиента"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize = 20f
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            isEnabled = !resolving
        }
        input = field
        root.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
        hint("Введите номер карты или наведите сканер на QR клиента")
        if (resolving) body("Проверяем карту…")
        errorMessage?.let { body(it) }
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
        primary("OK") { resolve(field.text.toString()) }
        btn("Продолжить без карты") { CardSession.clear(this); errorMessage = null; screen = Screen.ORDER; safeRender() }
        field.requestFocus()
        field.post { if (!isFinishing) { field.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) } }
    }

    private fun resolve(raw: String) {
        val code = raw.trim().removePrefix("6.7:").removePrefix("67coffee:").trim()
        if (code.isEmpty()) { input?.error = "Введите номер карты или отсканируйте QR"; return }
        if (resolving) return
        resolving = true; errorMessage = null; safeRender()
        thread(name = "loyalty-lookup") {
            val info = LoyaltyApi.resolve(code)
            runOnUiThread {
                resolving = false
                if (info.code.isNotEmpty()) {
                    CardSession.set(this, info); screen = Screen.ORDER; errorMessage = null
                    reloadProducts()
                } else {
                    CardSession.clear(this)
                    errorMessage = if (info.freeStatus == "ERROR") "Карта не найдена" else "Лояльность недоступна. Можно продолжить без карты."
                }
                safeRender()
            }
        }
    }

    private fun renderOrder() {
        title("6.7 Coffee")
        val card = CardSession.get(this)?.takeIf { it.code.isNotEmpty() }
        if (card != null) {
            root.addView(TextView(this).apply {
                text = "Карта ${card.cardCode.ifBlank { "—" }} · ${card.paidCups}/${card.cupsForFree} · ${card.cashbackKopecks / 100} ₽" + if (card.freeAvailable > 0) " · Подарок доступен" else ""
                textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF002FA7.toInt()); setPadding(0, 0, 0, dp(8))
            })
            btn("Сбросить клиента") { CardSession.clear(this); safeRender() }
        } else hint("Продажа без карты")
        title("Каталог")
        if (products.isEmpty()) hint("В каталоге Эвотор нет доступных товаров. Синхронизируйте номенклатуру и обновите меню.")
        products.forEach { p ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
            box.addView(TextView(this).apply { text = "${p.name} · ${p.priceRub.toPlainString()} ₽"; textSize = 17f; setTypeface(null, Typeface.BOLD) })
            if (!p.recipe.isNullOrBlank()) box.addView(TextView(this).apply { text = p.recipe; textSize = 12f })
            box.addView(Button(this).apply { text = "Добавить"; minHeight = dp(48); setOnClickListener {
                Cart.add(CartLine(p.uuid, p.name, p.priceRub, BigDecimal.ONE, p.recipe, p.freeEligible)); safeRender()
            } })
            root.addView(box)
        }
        divider()
        title("Корзина · ${Cart.all().sumOf { it.quantity.toInt() }} шт.")
        Cart.all().forEach { line ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = "${line.name} × ${line.quantity.stripTrailingZeros().toPlainString()}\n${line.lineTotal().toPlainString()} ₽"; textSize = 15f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply { text = "−"; setOnClickListener { Cart.setQty(line.productUuid, line.quantity.subtract(BigDecimal.ONE)); safeRender() } })
            row.addView(Button(this).apply { text = "+"; setOnClickListener { Cart.add(line.copy(quantity = BigDecimal.ONE)); safeRender() } })
            row.addView(Button(this).apply { text = "×"; setOnClickListener { Cart.remove(line.productUuid); safeRender() } })
            root.addView(row)
        }
        body("Итого: ${Cart.gross().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()} ₽")
        primary("К оплате") { checkout(card) }
        btn("Обновить каталог") { reloadProducts(); safeRender() }
        btn("Очистить корзину") { Cart.clear(); safeRender() }
        btn("Назад к карте") { screen = Screen.CARD; safeRender() }
    }

    private fun checkout(card: ru.sixthcup.evotor.data.CardInfo?) {
        if (Cart.isEmpty()) { Toast.makeText(this, "Корзина пуста", Toast.LENGTH_SHORT).show(); return }
        val eligible = Cart.all().any { it.freeEligible }
        if (card != null && card.freeAvailable > 0 && eligible) {
            AlertDialog.Builder(this).setTitle("Применить бесплатный стакан?")
                .setMessage("Бариста подтверждает использование подарка.")
                .setPositiveButton("Да") { _, _ -> askCashback(card, true) }
                .setNegativeButton("Нет") { _, _ -> askCashback(card, false) }.show()
        } else askCashback(card, false)
    }

    private fun askCashback(card: ru.sixthcup.evotor.data.CardInfo?, free: Boolean) {
        if (card != null && card.cashbackKopecks > 0 && card.reservationId != null) {
            AlertDialog.Builder(this).setTitle("Кэшбэк ${card.cashbackKopecks / 100} ₽")
                .setMessage("Выберите, как использовать кэшбэк")
                .setPositiveButton("Списать") { _, _ -> pay(free, true, card.cashbackKopecks) }
                .setNegativeButton("Копить") { _, _ -> pay(free, false, 0L) }.show()
        } else pay(free, false, 0L)
    }

    private fun pay(free: Boolean, spend: Boolean, amount: Long) {
        SellLauncher.openSellReceipt(this, free, spend, amount) { ok, msg ->
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            if (ok) awaitingPaymentReturn = true
        }
    }

    private fun title(t: String) = root.addView(TextView(this).apply { text = t; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF002FA7.toInt()); setPadding(0, 0, 0, dp(8)) })
    private fun hint(t: String) = root.addView(TextView(this).apply { text = t; textSize = 13f; setTextColor(0xFF64748B.toInt()); setPadding(0, 0, 0, dp(12)) })
    private fun body(t: String) = root.addView(TextView(this).apply { text = t; textSize = 15f; setPadding(0, 0, 0, dp(12)) })
    private fun btn(label: String, onClick: () -> Unit) = root.addView(Button(this).apply { text = label; minHeight = dp(48); setOnClickListener { onClick() }; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) } })
    private fun primary(label: String, onClick: () -> Unit) = root.addView(Button(this).apply { text = label; minHeight = dp(50); setBackgroundColor(0xFF002FA7.toInt()); setTextColor(0xFFFFFFFF.toInt()); setOnClickListener { onClick() }; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } })
    private fun divider() = root.addView(View(this).apply { setBackgroundColor(0xFFE2E8F0.toInt()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        try { val f = IntentFilter(ScannerReceiver.ACTION_INTERNAL_SCAN); if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, f, RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(scanReceiver, f) } catch (_: Throwable) {}
        if (awaitingPaymentReturn) {
            awaitingPaymentReturn = false
            AlertDialog.Builder(this).setTitle("Оплата завершена?")
                .setMessage("Подтвердите результат фискализации на терминале.")
                .setPositiveButton("Да, чек успешен") { _, _ ->
                    CardSession.clear(this); Cart.clear(); screen = Screen.CARD; errorMessage = null; safeRender()
                }
                .setNegativeButton("Нет, оставить заказ") { _, _ -> screen = Screen.ORDER; safeRender() }
                .setCancelable(false).show()
        }
    }
    override fun onPause() { try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}; super.onPause() }
}
