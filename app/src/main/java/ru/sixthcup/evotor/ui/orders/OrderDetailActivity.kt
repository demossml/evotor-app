package ru.sixthcup.evotor.ui.orders

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CartLine
import ru.sixthcup.evotor.data.InventoryRepository
import ru.sixthcup.evotor.data.PreorderPull
import ru.sixthcup.evotor.data.PreorderPullSession
import ru.sixthcup.evotor.net.OrderApi

/**
 * Cashier order detail: statuses + pull-to-receipt hook.
 * Layout built in code to avoid XML merge conflicts in this delivery package.
 *
 * Wire from MainActivity.handleScannedCode:
 *   if (OrderScanRouter.isOrderQr(code)) {
 *     val oid = OrderScanRouter.extractOid(code)
 *     startActivity(Intent(this, OrderDetailActivity::class.java).putExtra(EXTRA_OID, oid))
 *     return
 *   }
 */
class OrderDetailActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var order: OrderApi.Order? = null
    private var busy = false

    private lateinit var title: TextView
    private lateinit var statusView: TextView
    private lateinit var itemsView: TextView
    private lateinit var totalView: TextView
    private lateinit var cardView: TextView
    private lateinit var btnPrimary: Button
    private lateinit var btnReject: Button
    private lateinit var btnPull: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        title = TextView(this).apply { textSize = 22f }
        statusView = TextView(this).apply { textSize = 16f }
        cardView = TextView(this)
        itemsView = TextView(this)
        totalView = TextView(this).apply { textSize = 18f }
        btnPrimary = Button(this)
        btnReject = Button(this).apply { text = "Отклонить" }
        btnPull = Button(this).apply { text = "Подтянуть в чек" }
        col.addView(title)
        col.addView(statusView)
        col.addView(cardView)
        col.addView(itemsView)
        col.addView(totalView)
        col.addView(btnPrimary)
        col.addView(btnPull)
        col.addView(btnReject)
        root.addView(col)
        setContentView(root)

        btnPrimary.setOnClickListener { onPrimary() }
        btnReject.setOnClickListener { onReject() }
        btnPull.setOnClickListener { onPull() }

        val oid = intent.getStringExtra(EXTRA_OID)
        val code = intent.getStringExtra(EXTRA_CODE)
        Thread {
            val result = when {
                !oid.isNullOrBlank() -> OrderApi.resolveByOid(oid)
                !code.isNullOrBlank() -> OrderApi.resolveByCode(code)
                else -> OrderApi.Result.Err("bad_request", "Нет oid/code")
            }
            handler.post {
                when (result) {
                    is OrderApi.Result.Ok -> bind(result.value)
                    is OrderApi.Result.Err -> {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }.start()
    }

    private fun bind(o: OrderApi.Order) {
        order = o
        title.text = "Заказ ${o.shortCode}"
        statusView.text = "Статус: ${o.status}"
        cardView.text = if (o.cardId.isNullOrBlank()) "Карта не привязана" else "Карта привязана"
        itemsView.text = o.items.joinToString("\n\n") {
            "• ${it.name} ×${it.quantity}\n  ${it.configText}\n  ${it.unitPriceEstimate} ₽"
        }
        totalView.text = "Оценка: ${o.estimateTotal} ₽"
        btnPrimary.text = when (o.status) {
            "draft" -> "Принять"
            "accepted" -> "Готовится"
            "cooking" -> "Готов"
            else -> "—"
        }
        btnPrimary.isEnabled = o.status in setOf("draft", "accepted", "cooking")
        btnPull.isEnabled = o.status in setOf("accepted", "cooking", "ready")
    }

    private fun onPrimary() {
        val o = order ?: return
        if (busy) return
        val next = when (o.status) {
            "draft" -> "accepted"
            "accepted" -> "cooking"
            "cooking" -> "ready"
            else -> return
        }
        busy = true
        Thread {
            val r = OrderApi.setStatus(o.id, next)
            handler.post {
                busy = false
                when (r) {
                    is OrderApi.Result.Ok -> bind(r.value)
                    is OrderApi.Result.Err -> Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun onReject() {
        val o = order ?: return
        if (busy) return
        val input = android.widget.EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Причина отклонения")
            .setView(input)
            .setPositiveButton("Отклонить") { _, _ ->
                val reason = input.text?.toString()?.trim().orEmpty()
                if (reason.isEmpty()) {
                    Toast.makeText(this, "Укажите причину", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                busy = true
                Thread {
                    val r = OrderApi.reject(o.id, reason)
                    handler.post {
                        busy = false
                        when (r) {
                            is OrderApi.Result.Ok -> {
                                Toast.makeText(this, "Отклонён", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                            is OrderApi.Result.Err -> Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun onPull() {
        val o = order ?: return
        if (busy) return
        busy = true
        btnPull.isEnabled = false
        // Номенклатура и корзина — как в ручном пути: тот же InventoryRepository и тот же Cart.
        Thread {
            val catalog = try {
                InventoryRepository.load(this)
            } catch (t: Throwable) {
                Log.w(TAG, "pull_catalog_failed", t)
                emptyList()
            }
            val byUuid = catalog.associateBy { it.uuid }
            val already = Cart.all().map { it.cartKey }.toSet()
            val plan = PreorderPull.plan(o.id, o.items, byUuid, already)
            // Cart — синглтон процесса: строки сразу видны на кассовом экране после finish().
            plan.lines.forEach { l ->
                Cart.add(
                    CartLine(
                        productUuid = l.productUuid,
                        name = l.name,
                        priceRub = l.priceRub,
                        quantity = l.quantity,
                        recipe = l.recipe,
                        freeEligible = l.freeEligible,
                        cartKey = l.cartKey,
                    ),
                )
            }
            handler.post {
                busy = false
                if (plan.hasAnything) {
                    // Связь с чеком: после успешной продажи вызовем markPaid (MainActivity, onResume).
                    PreorderPullSession.set(
                        o.id,
                        o.shortCode,
                        plan.lines.map { PreorderPullSession.Item(it.productUuid, it.name, it.quantity.toInt()) },
                    )
                    Log.i(TAG, "pull_ok order=${o.id} lines=${plan.added} skipped=${plan.missingUuid.size + plan.notFound.size}")
                    Toast.makeText(this, PreorderPull.notice(plan), Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Log.w(TAG, "pull_empty order=${o.id} missing=${plan.missingUuid.size} notFound=${plan.notFound.size}")
                    btnPull.isEnabled = true
                    Toast.makeText(this, PreorderPull.notice(plan), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    companion object {
        private const val TAG = "OrderDetail"
        const val EXTRA_OID = "oid"
        const val EXTRA_CODE = "code"
    }
}
