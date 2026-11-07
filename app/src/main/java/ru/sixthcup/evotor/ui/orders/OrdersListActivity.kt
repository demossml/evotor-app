package ru.sixthcup.evotor.ui.orders

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.sixthcup.evotor.net.OrderApi

/** Incoming pre-orders list + 6-digit code search. Polls every 10s. */
class OrdersListActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var listCol: LinearLayout
    private lateinit var empty: TextView
    private val knownIds = mutableSetOf<String>()
    private var poll: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val codeInput = EditText(this).apply {
            hint = "6-значный код"
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        }
        val findBtn = Button(this).apply { text = "Найти" }
        findBtn.setOnClickListener {
            val code = codeInput.text?.toString()?.trim().orEmpty()
            if (!code.matches(Regex("\\d{6}"))) {
                Toast.makeText(this, "Введите 6 цифр", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, OrderDetailActivity::class.java).putExtra(OrderDetailActivity.EXTRA_CODE, code))
        }
        empty = TextView(this).apply { text = "Нет активных заказов"; textSize = 16f }
        listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listCol) }
        root.addView(TextView(this).apply { text = "Заказы"; textSize = 22f })
        root.addView(codeInput)
        root.addView(findBtn)
        root.addView(empty)
        root.addView(scroll)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        poll = object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 10_000)
            }
        }
        handler.postDelayed(poll!!, 10_000)
    }

    override fun onPause() {
        super.onPause()
        poll?.let { handler.removeCallbacks(it) }
        poll = null
    }

    private fun refresh() {
        Thread {
            when (val r = OrderApi.active()) {
                is OrderApi.Result.Ok -> handler.post { render(r.value) }
                is OrderApi.Result.Err -> handler.post {
                    Toast.makeText(this, r.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun render(rows: List<OrderApi.ActiveRow>) {
        listCol.removeAllViews()
        empty.visibility = if (rows.isEmpty()) TextView.VISIBLE else TextView.GONE
        for (row in rows) {
            if (row.id !in knownIds) {
                knownIds.add(row.id)
                // Notification channel wiring is host-app responsibility; toast for new ids this session
                if (knownIds.size > rows.size) {
                    Toast.makeText(this, "Новый заказ ${row.shortCode}", Toast.LENGTH_SHORT).show()
                }
            }
            val tv = TextView(this).apply {
                text = "${row.shortCode} · ${row.status} · ${row.itemsCount} поз. · ${row.estimateTotal} ₽"
                textSize = 16f
                setPadding(0, 24, 0, 24)
                setOnClickListener {
                    startActivity(
                        Intent(this@OrdersListActivity, OrderDetailActivity::class.java)
                            .putExtra(OrderDetailActivity.EXTRA_OID, row.id),
                    )
                }
            }
            listCol.addView(tv)
        }
    }
}
