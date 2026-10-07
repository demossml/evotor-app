package ru.sixthcup.evotor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private enum class Screen { CARD, CATALOG, CART }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val note = intent.getStringExtra(ScannerReceiver.EXTRA_NOTE).orEmpty()
            Toast.makeText(this@MainActivity, if (note.isEmpty()) "Карта принята" else note, Toast.LENGTH_SHORT).show()
            safeRender()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            val frame = findViewById<android.widget.FrameLayout>(R.id.root)
            val scroll = ScrollView(this)
            root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(24))
            }
            scroll.addView(root)
            frame.addView(scroll)
            // Inventory NOT loaded here (crash fix + docs: load on demand)
            safeRender()
        } catch (e: Throwable) {
            Toast.makeText(this, "Старт: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun safeRender() {
        try { render() } catch (e: Throwable) {
            Toast.makeText(this, "UI: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun reloadProducts() {
        products = try { InventoryRepository.load(this) } catch (e: Throwable) {
            Toast.makeText(this, "Номенклатура: ${e.message}", Toast.LENGTH_LONG).show()
            emptyList()
        }
    }

    private fun render() {
        root.removeAllViews()
        when (screen) {
            Screen.CARD -> renderCard()
            Screen.CATALOG -> renderCatalog()
            Screen.CART -> renderCart()
        }
    }

    private fun renderCard() {
        title("6.7 Coffee")
        hint("Отсканируйте QR гостя или введите номер карты. Бонусы проверяет и резервирует сервер.")
        val info = CardSession.get(this)
        body(
            when {
                info == null || info.code.isEmpty() -> "Карта не выбрана"
                info.freeStatus == "OFFLINE" -> "Нет связи с сервером: стакан засчитается после чека, бонусы сейчас недоступны."
                else -> buildString {
                    append("Карта ${info.cardCode.padStart(4, '0')}\n")
                    append("${info.paidCups} из ${info.cupsForFree} стаканов\n")
                    append(when (info.freeStatus) {
                        "RESERVED" -> "Бесплатный стакан: ДА (зарезервирован)\n"
                        "ALREADY_RESERVED" -> "Бесплатный стакан уже зарезервирован на другой кассе\n"
                        else -> if (info.code.matches(Regex("\\d{1,18}"))) "По номеру карты бонусы не списываются — только начисление\n" else "Бесплатный стакан: нет\n"
                    })
                    if (info.cashbackKopecks > 0) append("Кэшбэк: ${info.cashbackKopecks / 100} ₽\n")
                }
            },
        )
        val input = EditText(this).apply {
            hint = "Номер карты"; inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        root.addView(input)
        btn("Найти по номеру") {
            val c = input.text.toString().trim()
            if (!c.matches(Regex("\\d{1,18}"))) { input.error = "Только цифры"; return@btn }
            lookup(c)
        }
        btn("Сбросить карту") { CardSession.clear(this); safeRender() }
        primary("К меню товаров") {
            screen = Screen.CATALOG; reloadProducts(); safeRender()
        }
    }

    private fun lookup(code: String) {
        Toast.makeText(this, "Проверяем карту…", Toast.LENGTH_SHORT).show()
        thread(name = "loyalty-lookup") {
            val info = LoyaltyApi.resolve(code)
            runOnUiThread {
                if (info.code.isNotEmpty()) CardSession.set(this, info) else CardSession.clear(this)
                if (!info.note.isNullOrEmpty()) Toast.makeText(this, info.note, Toast.LENGTH_LONG).show()
                safeRender()
            }
        }
    }

    private fun renderCatalog() {
        title("Меню")
        hint(if (products.isEmpty()) "Пусто — sync товаров в Эвотор и обновите номенклатуру" else "Номенклатура терминала")
        cardBar()
        products.forEach { p ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }
            box.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            box.addView(TextView(this).apply { text = "${p.name} · ${p.priceRub} ₽"; setTypeface(null, Typeface.BOLD) })
            if (!p.recipe.isNullOrBlank()) box.addView(TextView(this).apply { text = p.recipe; setTextColor(0xFF64748B.toInt()) })
            box.addView(Button(this).apply {
                text = "В чек"
                setOnClickListener {
                    Cart.add(CartLine(p.uuid, p.name, p.priceRub, BigDecimal.ONE, p.recipe))
                    Toast.makeText(this@MainActivity, "+ ${p.name}", Toast.LENGTH_SHORT).show()
                }
            })
            root.addView(box)
        }
        primary("Корзина (${Cart.all().size})") { screen = Screen.CART; safeRender() }
        btn("Карта гостя") { screen = Screen.CARD; safeRender() }
        btn("Обновить меню") { reloadProducts(); safeRender() }
    }

    private fun renderCart() {
        title("Корзина"); cardBar()
        if (Cart.isEmpty()) hint("Пусто")
        else {
            Cart.all().forEach { line ->
                root.addView(TextView(this).apply { text = "${line.name} × ${line.quantity} = ${line.lineTotal()} ₽"; setPadding(0, dp(6), 0, dp(6)) })
            }
            body("Итого: ${Cart.gross()} ₽")
        }
        primary("Пробить чек") {
            SellLauncher.openSellReceipt(this) { ok, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (ok) { screen = Screen.CATALOG; safeRender() }
            }
        }
        btn("К меню") { screen = Screen.CATALOG; safeRender() }
        btn("Очистить") { Cart.clear(); safeRender() }
    }

    private fun cardBar() {
        root.addView(TextView(this).apply {
            text = CardSession.get(this@MainActivity)?.takeIf { it.code.isNotEmpty() }?.let { "Гость: карта ${it.cardCode.padStart(4, '0')}" } ?: "Гость: не выбран"
            setTextColor(0xFF002FA7.toInt()); setPadding(0, 0, 0, dp(8))
        })
    }
    private fun title(t: String) = root.addView(TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTypeface(null, Typeface.BOLD)
        setTextColor(0xFF002FA7.toInt()); setPadding(0, 0, 0, dp(8))
    })
    private fun hint(t: String) = root.addView(TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(0xFF64748B.toInt()); setPadding(0, 0, 0, dp(12))
    })
    private fun body(t: String) = root.addView(TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(0, 0, 0, dp(12))
    })
    private fun btn(label: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = label; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    })
    private fun primary(label: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = label; setBackgroundColor(0xFF002FA7.toInt()); setTextColor(0xFFFFFFFF.toInt())
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    })
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onResume() {
        super.onResume()
        try {
            val f = IntentFilter(ScannerReceiver.ACTION_INTERNAL_SCAN)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, f, RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(scanReceiver, f)
        } catch (_: Throwable) {}
    }
    override fun onPause() {
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        super.onPause()
    }
}
