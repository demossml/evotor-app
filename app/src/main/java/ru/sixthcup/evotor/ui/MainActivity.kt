package ru.sixthcup.evotor.ui
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.sixthcup.evotor.R
import ru.sixthcup.evotor.data.CardSession
import ru.sixthcup.evotor.scanner.ScannerReceiver
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = intent.getStringExtra(ScannerReceiver.EXTRA_CODE) ?: return
            CardSession.set(this@MainActivity, code)
            status.text = "Карта готова к следующей продаже"
            Toast.makeText(this@MainActivity, "Клиент распознан", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val root = findViewById<LinearLayout>(R.id.content)
        root.removeAllViews()
        root.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        root.setPadding(pad, pad, pad, pad)
        status = TextView(this).apply { text = statusText(); textSize = 18f }
        val cardInput = EditText(this).apply {
            hint = "Например, 0042"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            textSize = 20f
        }
        val bind = Button(this).apply {
            text = "Привязать карту"
            setOnClickListener {
                val code = cardInput.text.toString().trim()
                if (!code.matches(Regex("\\d{1,18}"))) {
                    cardInput.error = "Введите только цифры номера карты"
                    return@setOnClickListener
                }
                CardSession.set(this@MainActivity, code)
                cardInput.text?.clear()
                status.text = statusText()
                Toast.makeText(this@MainActivity, "Клиент готов", Toast.LENGTH_SHORT).show()
            }
        }
        val clear = Button(this).apply {
            text = "Сбросить клиента"
            setOnClickListener {
                CardSession.clear(this@MainActivity)
                cardInput.text?.clear()
                status.text = statusText()
            }
        }
        val hint = TextView(this).apply {
            text = "Сканируйте QR гостя или введите номер карты\nТовары выбираются через меню Эвотора — APK использует его номенклатуру"
            textSize = 14f
        }
        listOf(
            TextView(this).apply { text = "6.7 Coffee"; textSize = 22f; setTextColor(0xFF002FA7.toInt()) },
            status, hint, cardInput, bind, clear
        ).forEach { root.addView(it) }
    }
    private fun statusText(): String {
        val c = CardSession.get(this)
        return if (c != null) "Клиент распознан\nДалее — продажа в меню Эвотор" else "Клиент не выбран"
    }
    override fun onResume() {
        super.onResume()
        val f = IntentFilter(ScannerReceiver.ACTION_INTERNAL_SCAN)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, f, RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(scanReceiver, f)
        if (::status.isInitialized) status.text = statusText()
    }
    override fun onPause() {
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        super.onPause()
    }
}
