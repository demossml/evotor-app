package ru.sixthcup.evotor.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.sixthcup.evotor.data.CardSession
import ru.sixthcup.evotor.net.LoyaltyApi
import kotlin.concurrent.thread

/** Receives the Evotor barcode scan, asks the backend (identity + bonus reservation), keeps the answer for this sale. */
class ScannerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "ru.evotor.devices.ScannedCode") return
        val code = intent.getStringExtra("ru.evotor.devices.extra.SCANNED_CODE")?.trim() ?: return
        if (code.isEmpty() || code.length > 4000) return
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "loyalty-resolve") {
            try {
                val info = LoyaltyApi.resolve(code)
                if (info.code.isNotEmpty()) CardSession.set(app, info) else CardSession.clear(app)
                app.sendBroadcast(
                    Intent(ACTION_INTERNAL_SCAN).setPackage(app.packageName)
                        .putExtra(EXTRA_NOTE, info.note ?: ""),
                )
            } finally {
                pending.finish()
            }
        }
    }
    companion object {
        const val ACTION_INTERNAL_SCAN = "ru.sixthcup.evotor.INTERNAL_SCAN"
        const val EXTRA_NOTE = "note"
    }
}
