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
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.sixthcup.evotor.R
import ru.sixthcup.evotor.data.CardSession
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CartLine
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.InventoryRepository
import ru.sixthcup.evotor.integration.CardTokenVerifier
import ru.sixthcup.evotor.scanner.ScannerReceiver
import ru.sixthcup.evotor.sell.SellLauncher
import java.math.BigDecimal

/**
 * Full cashier UI:
 * 1) Bind guest (QR or short code) — no server TLS
 * 2) Catalog from terminal inventory (Cloud → terminal)
 * 3) Cart + pay → OpenReceipt on Evotor
 */
class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var products: List<CatalogProduct> = emptyList()
    private var screen: Screen = Screen.CARD

    private enum class Screen { CARD, CATALOG, CART }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = intent.getStringExtra(ScannerReceiver.EXTRA_CODE) ?: return
            CardSession.set(this@MainActivity, code)
            Toast.makeText(this@MainActivity, "Карта принята", Toast.LENGTH_SHORT).show()
            if (screen == Screen.CARD) render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val frame = findViewById<android.widget.FrameLayout>(R.id.root)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(root)
        frame.addView(scroll)
        reloadProducts()
        render()
    }

    private fun reloadProducts() {
        products = try {
            InventoryRepository.load(this)
        } catch (e: Throwable) {
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
        hint("Привяжите карту гостя: QR из приложения или короткий номер. Без запроса к серверу 6.7.")
        val status = body(
            when {
                CardSession.get(this) == null -> "Карта не привязана"
                CardSession.get(this)!!.matches(Regex("\\d{1,18}")) -> "Карта: номер ${CardSession.get(this)}"
                CardTokenVerifier.verify(CardSession.get(this)!!) != null -> {
                    val v = CardTokenVerifier.verify(CardSession.get(this)!!)!!
                    "Карта OK · кэшбэк ${v.cashbackKopecks / 100.0} ₽ · стаканы ${v.paidCups}"
                }
                else -> "QR принят (проверка подписи при оплате)"
            },
        )
        root.addView(status)

        val input = EditText(this).apply {
            hint = "Номер карты"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        root.addView(input)

        btn("Привязать номер") {
            val c = input.text.toString().trim()
            if (!c.matches(Regex("\\d{1,18}"))) {
                input.error = "Только цифры"
                return@btn
            }
            CardSession.set(this, c)
            render()
        }
        btn("Сбросить карту") {
            CardSession.clear(this)
            render()
        }
        primary("К меню товаров") {
            screen = Screen.CATALOG
            reloadProducts()
            render()
        }
    }

    private fun renderCatalog() {
        title("Меню")
        hint(
            if (products.isEmpty())
                "Пусто. Сначала admin sync товаров в Эвотор и обновите номенклатуру на терминале."
            else
                "Товары с кассы (Inventory). Рецепт — если передан в Cloud.",
        )
        cardBar()
        products.forEach { p ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }
            val pad = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            pad.bottomMargin = dp(8)
            box.layoutParams = pad
            box.addView(TextView(this).apply {
                text = "${p.name} · ${p.priceRub} ₽"
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            if (!p.recipe.isNullOrBlank()) {
                box.addView(TextView(this).apply {
                    text = p.recipe
                    setTextColor(0xFF64748B.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
            }
            box.addView(Button(this).apply {
                text = "В чек"
                setOnClickListener {
                    Cart.add(
                        CartLine(
                            productUuid = p.uuid,
                            name = p.name,
                            priceRub = p.priceRub,
                            quantity = BigDecimal.ONE,
                            recipe = p.recipe,
                        ),
                    )
                    Toast.makeText(this@MainActivity, "+ ${p.name}", Toast.LENGTH_SHORT).show()
                }
            })
            root.addView(box)
        }
        primary("Корзина (${Cart.all().size})") {
            screen = Screen.CART
            render()
        }
        btn("Карта гостя") {
            screen = Screen.CARD
            render()
        }
        btn("Обновить меню") {
            reloadProducts()
            render()
        }
    }

    private fun renderCart() {
        title("Корзина")
        cardBar()
        if (Cart.isEmpty()) {
            hint("Пусто")
        } else {
            Cart.all().forEach { line ->
                root.addView(TextView(this).apply {
                    text = "${line.name} × ${line.quantity} = ${line.lineTotal()} ₽"
                    setPadding(0, dp(6), 0, dp(6))
                })
            }
            body("Итого: ${Cart.gross()} ₽")
        }
        primary("Пробить чек") {
            if (CardSession.get(this) == null) {
                Toast.makeText(this, "Сначала привяжите карту (или продолжите без лояльности)", Toast.LENGTH_LONG).show()
            }
            SellLauncher.openSellReceipt(this) { ok, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (ok) {
                    screen = Screen.CATALOG
                    render()
                }
            }
        }
        btn("К меню") {
            screen = Screen.CATALOG
            render()
        }
        btn("Очистить") {
            Cart.clear()
            render()
        }
    }

    private fun cardBar() {
        val c = CardSession.get(this)
        root.addView(TextView(this).apply {
            text = if (c == null) "Гость: не выбран" else "Гость: привязан"
            setTextColor(0xFF002FA7.toInt())
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun title(t: String) {
        root.addView(TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF002FA7.toInt())
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun hint(t: String) {
        root.addView(TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 0, 0, dp(12))
        })
    }

    private fun body(t: String): TextView {
        val v = TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(v)
        return v
    }

    private fun btn(label: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6)
            layoutParams = lp
        })
    }

    private fun primary(label: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            setBackgroundColor(0xFF002FA7.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(ScannerReceiver.ACTION_INTERNAL_SCAN)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, f, RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(scanReceiver, f)
    }

    override fun onPause() {
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        super.onPause()
    }
}
