package ru.sixthcup.evotor.ui.orders

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ru.sixthcup.evotor.data.PreorderQueue
import ru.sixthcup.evotor.net.OrderApi

/**
 * Очередь предзаказов точки: карточки со статусом и ETA, автообновление раз в 10 с
 * (только пока экран на переднем плане) и pull-to-refresh, ввод 6 цифр для поиска заказа.
 */
class OrdersListActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var codeInput: EditText
    private var busy = false

    private val ticker = object : Runnable {
        override fun run() {
            load(silent = true)
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            TextView(this).apply { text = "Предзаказы"; textSize = 22f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(Button(this).apply {
            text = "Закрыть"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        col.addView(head)

        // Поиск по 6 цифрам — тот же путь, что скан QR: панель заказа.
        val search = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        codeInput = EditText(this).apply {
            hint = "6 цифр"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        }
        search.addView(codeInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        search.addView(Button(this).apply {
            text = "Найти"
            isAllCaps = false
            setOnClickListener {
                val code = codeInput.text?.toString()?.trim().orEmpty()
                if (code.length != 6 || code.any { !it.isDigit() }) {
                    Toast.makeText(this@OrdersListActivity, "Введите 6 цифр", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startActivity(
                    Intent(this@OrdersListActivity, OrderDetailActivity::class.java)
                        .putExtra(OrderDetailActivity.EXTRA_CODE, code),
                )
            }
        })
        col.addView(search)

        empty = TextView(this).apply {
            text = "Активных предзаказов нет"
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }
        col.addView(empty)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        swipe = SwipeRefreshLayout(this).apply {
            setOnRefreshListener { load(silent = false) }
            addView(
                android.widget.ScrollView(this@OrdersListActivity).apply { addView(list) },
            )
        }
        col.addView(swipe, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(col)
        load(silent = false)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(ticker, REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun load(silent: Boolean) {
        if (busy) return
        busy = true
        Thread {
            val r = OrderApi.active()
            handler.post {
                busy = false
                swipe.isRefreshing = false
                when (r) {
                    is OrderApi.Result.Ok -> render(PreorderQueue.sortForQueue(r.value))
                    is OrderApi.Result.Err -> {
                        Log.w(TAG, "active_failed err=${r.error}")
                        if (!silent) {
                            Toast.makeText(this, PreorderQueue.errorText(r.error), Toast.LENGTH_LONG).show()
                        }
                        if (list.childCount == 0) empty.visibility = TextView.VISIBLE
                    }
                }
            }
        }.start()
    }

    private fun render(rows: List<OrderApi.ActiveRow>) {
        list.removeAllViews()
        empty.visibility = if (rows.isEmpty()) TextView.VISIBLE else TextView.GONE
        rows.forEach { row ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 14, 16, 14)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFFFFFFF.toInt())
                    cornerRadius = 12f
                    setStroke(1, 0xFFE7DFD3.toInt())
                }
                isClickable = true
                setOnClickListener {
                    startActivity(
                        Intent(this@OrdersListActivity, OrderDetailActivity::class.java)
                            .putExtra(OrderDetailActivity.EXTRA_OID, row.id),
                    )
                }
            }
            card.addView(TextView(this).apply {
                text = "Заказ ${row.shortCode} · ${PreorderQueue.statusLabel(row.status)}"
                textSize = 17f
            })
            card.addView(TextView(this).apply {
                val eta = PreorderQueue.etaText(row.status, row.etaReadyAt, row.serverNow)
                text = listOfNotNull(
                    "Оценка ${row.estimateTotal} ₽",
                    "${row.itemsCount} поз.",
                    eta.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                textSize = 13f
            })
            list.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 10 })
        }
    }

    companion object {
        private const val TAG = "OrdersList"
        private const val REFRESH_MS = 10_000L
    }
}
