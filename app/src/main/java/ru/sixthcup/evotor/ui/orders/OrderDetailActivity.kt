package ru.sixthcup.evotor.ui.orders

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CartLine
import ru.sixthcup.evotor.data.InventoryRepository
import ru.sixthcup.evotor.data.OrderBenefit
import ru.sixthcup.evotor.data.PreorderPullSession
import ru.sixthcup.evotor.data.PreorderPull
import ru.sixthcup.evotor.data.PreorderQueue
import ru.sixthcup.evotor.net.OrderApi
import java.math.BigDecimal

/**
 * Панель предзаказа: состав, ETA, статусы, «В чек».
 *
 * Статусы строго по цепочке `draft → accepted → cooking → ready`; кнопки строит
 * [PreorderQueue.actions]. «В чек» наполняет корзину через [InventoryRepository] (цены терминала)
 * и ничего не фискализирует — оплата остаётся на кассе. После успешного SELL другой экран
 * вызовет `markPaid`.
 */
class OrderDetailActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var order: OrderApi.Order? = null
    private var busy = false
    /** `409 benefit_conflict`: бонус гостя уже занят другим чеком — «В чек» запрещён до администратора. */
    private var pullBlocked = false

    private lateinit var title: TextView
    private lateinit var statusView: TextView
    private lateinit var etaView: TextView
    private lateinit var cardView: TextView
    private lateinit var itemsView: TextView
    private lateinit var estimateView: TextView
    private lateinit var warningsView: TextView
    private lateinit var buttons: LinearLayout

    private val ticker = object : Runnable {
        override fun run() {
            if (PreorderQueue.canPull(order?.status) || order?.status == PreorderQueue.READY) reload()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }
        title = TextView(this).apply { textSize = 22f }
        statusView = TextView(this).apply { textSize = 16f }
        etaView = TextView(this).apply { textSize = 14f }
        cardView = TextView(this)
        itemsView = TextView(this).apply { textSize = 14f; setPadding(0, 12, 0, 0) }
        estimateView = TextView(this).apply { textSize = 16f; setPadding(0, 12, 0, 4) }
        warningsView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFA2551F.toInt())
            setPadding(0, 8, 0, 8)
        }
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(title)
        col.addView(statusView)
        col.addView(etaView)
        col.addView(cardView)
        col.addView(itemsView)
        col.addView(estimateView)
        col.addView(warningsView)
        col.addView(buttons)
        root.addView(col)
        setContentView(root)

        load()
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(ticker, REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun load() {
        val oid = intent.getStringExtra(EXTRA_OID)
        val code = intent.getStringExtra(EXTRA_CODE)
        fetch(oid, code)
    }

    private fun reload() {
        order?.let { fetch(it.id, null) }
    }

    private fun fetch(oid: String?, code: String?) {
        if (busy) return
        busy = true
        Thread {
            val result = when {
                !oid.isNullOrBlank() -> OrderApi.resolveByOid(oid)
                !code.isNullOrBlank() -> OrderApi.resolveByCode(code)
                else -> OrderApi.Result.Err("bad_request", "Нет oid/code")
            }
            handler.post {
                busy = false
                when (result) {
                    is OrderApi.Result.Ok -> bind(result.value)
                    is OrderApi.Result.Err -> {
                        Log.w(TAG, "resolve_failed err=${result.error}")
                        if (result.error == "benefit_conflict") pullBlocked = true
                        if (order == null) {
                            showFatal(result.error, result.message)
                        } else {
                            // Автообновление: заказ мог уйти в paid/expired — просто показываем статус.
                            Toast.makeText(this, PreorderQueue.errorText(result.error), Toast.LENGTH_SHORT).show()
                            if (pullBlocked) renderButtons(order!!)
                        }
                    }
                }
            }
        }.start()
    }

    private fun showFatal(errorCode: String, serverMessage: String) {
        val text = PreorderQueue.errorText(errorCode)
        AlertDialog.Builder(this)
            .setTitle("Заказ недоступен")
            .setMessage(if (text == serverMessage) text else "$text\n\n$serverMessage")
            .setPositiveButton("Закрыть") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun bind(o: OrderApi.Order) {
        order = o
        title.text = "Заказ ${o.shortCode}"
        statusView.text = "Статус: ${PreorderQueue.statusLabel(o.status)}"
        val eta = PreorderQueue.etaText(o.status, o.etaReadyAt, o.serverNow)
        etaView.text = when {
            o.status == PreorderQueue.PAID -> "Оплачен"
            eta.isBlank() -> ""
            eta == "Готов" -> "Готов к выдаче"
            eta == "уточняем" -> "Готовим, уточняем"
            else -> "Готов через $eta"
        }
        cardView.text = if (o.cardId.isNullOrBlank()) "Карта не привязана" else "Карта ${o.cardId} привязана"
        itemsView.text = o.items.joinToString("\n\n") { i ->
            val head = "• ${i.name} ×${i.quantity} — ${i.unitPriceEstimate} ₽"
            val cfg = i.configText.takeIf { it.isNotBlank() }?.let { "\n  $it" } ?: ""
            val mods = i.modifiers
                .filter { it.sendToReceipt }
                .joinToString("") { m -> "\n  + ${m.name}" }
            head + cfg + mods
        }
        estimateView.text = run {
            val money = PreorderQueue.money(o.estimateTotal, o.benefit, o.benefitTotal, o.quoteTotal)
            if (!money.hasBenefit) {
                return@run "Оценка гостя: ${money.estimateRub} ₽\nФинальная цена — по номенклатуре Эвотора"
            }
            buildString {
                append("Оценка гостя: ${money.estimateRub} ₽")
                append("\n${money.benefitLine}")
                append("\nИтог: ${money.totalRub} ₽")
                append("\nИтог подтверждает касса")
            }
        }
        val warns = o.warnings + o.items.filter { it.evotorProductUuid.isNullOrBlank() }
            .map { "${it.name} — нет UUID на точке" } +
            (if (pullBlocked) listOf("Бонус гостя уже применён в другом чеке — позовите администратора") else emptyList())
        warningsView.text = warns.joinToString("\n")
        warningsView.visibility = if (warns.isEmpty()) TextView.GONE else TextView.VISIBLE
        renderButtons(o)
    }

    private fun renderButtons(o: OrderApi.Order) {
        buttons.removeAllViews()
        val actions = PreorderQueue.actions(o.status)
        if (o.status == PreorderQueue.PAID) {
            buttons.addView(actionButton("Закрыть", primary = true) { finish() })
            return
        }
        // Основной переход — первым и крупным, остальные ниже.
        PreorderQueue.nextStatus(o.status)?.let { next ->
            buttons.addView(
                actionButton(PreorderQueue.actionLabel(actionOf(next)), primary = true) { advance(next) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = 8 },
            )
        }
        if (PreorderQueue.Action.PULL in actions && !pullBlocked) {
            buttons.addView(
                actionButton("В чек", primary = PreorderQueue.nextStatus(o.status) == null) { onPull() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = 8 },
            )
        }
        if (PreorderQueue.Action.REJECT in actions) {
            buttons.addView(
                actionButton("Отклонить", primary = false) { onReject() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = 8 },
            )
        }
    }

    private fun actionOf(nextStatus: String): PreorderQueue.Action = when (nextStatus) {
        PreorderQueue.ACCEPTED -> PreorderQueue.Action.ACCEPT
        PreorderQueue.COOKING -> PreorderQueue.Action.COOKING
        else -> PreorderQueue.Action.READY
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = if (primary) 17f else 15f
        isAllCaps = false
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun advance(next: String) {
        val o = order ?: return
        if (busy) return
        busy = true
        buttons.isEnabled = false
        Thread {
            val r = OrderApi.setStatus(o.id, next)
            handler.post {
                busy = false
                buttons.isEnabled = true
                when (r) {
                    is OrderApi.Result.Ok -> bind(r.value)
                    is OrderApi.Result.Err -> {
                        Log.w(TAG, "status_failed err=${r.error} next=$next")
                        Toast.makeText(this, PreorderQueue.errorText(r.error), Toast.LENGTH_SHORT).show()
                        // invalid_transition — просто перечитываем заказ и перерисовываем кнопки.
                        reload()
                    }
                }
            }
        }.start()
    }

    private fun onReject() {
        val o = order ?: return
        if (busy) return
        val input = EditText(this)
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
                                Toast.makeText(this, "Заказ отклонён", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                            is OrderApi.Result.Err ->
                                Toast.makeText(this, PreorderQueue.errorText(r.error), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** «В чек»: наполнить корзину по номенклатуре терминала. Ничего не фискализируем. */
    private fun onPull() {
        val o = order ?: return
        if (busy) return
        if (pullBlocked) {
            Toast.makeText(this, PreorderQueue.errorText("benefit_conflict"), Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        buttons.isEnabled = false
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
            // Опции без UUID не попадают в чек отдельной позицией — кассир увидит их в диалоге.
            if (plan.modifierNoUuid.isNotEmpty()) {
                Log.w(
                    TAG,
                    "pull_modifier_no_uuid order=${o.id.take(8)} count=${plan.modifierNoUuid.size}",
                )
            }
            handler.post {
                busy = false
                buttons.isEnabled = true
                if (!plan.hasAnything) {
                    Log.w(TAG, "pull_empty order=${o.id} missing=${plan.missingUuid.size} notFound=${plan.notFound.size}")
                    AlertDialog.Builder(this)
                        .setTitle("Позиции не добавлены")
                        .setMessage(PreorderPull.notice(plan))
                        .setPositiveButton("Понятно", null)
                        .show()
                    return@post
                }
                PreorderPullSession.set(
                    o.id,
                    o.shortCode,
                    plan.lines.map { PreorderPullSession.Item(it.productUuid, it.name, it.quantity.toInt()) },
                    // Льгота заказа едет в чек вместе с составом: касса снимет её и напишет sc.preorder.
                    OrderBenefit.from(o),
                )
                Log.i(TAG, "pull_ok order=${o.id} lines=${plan.added} mods=${plan.lines.count { it.isModifier }}")
                AlertDialog.Builder(this)
                    .setTitle("Добавлено в чек")
                    .setMessage(PreorderPull.notice(plan))
                    .setPositiveButton("К кассе") { _, _ ->
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                    .setCancelable(false)
                    .show()
            }
        }.start()
    }

    companion object {
        private const val TAG = "OrderDetail"
        private const val REFRESH_MS = 10_000L
        const val EXTRA_OID = "oid"
        const val EXTRA_CODE = "code"
    }
}
