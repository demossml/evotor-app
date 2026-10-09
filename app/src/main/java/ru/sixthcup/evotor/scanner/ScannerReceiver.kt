package ru.sixthcup.evotor.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Forwards the scanner payload to the focused barista flow; parsing and resolve live in Activity. */
class ScannerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "ru.evotor.devices.ScannedCode") return
        val code = intent.getStringExtra("ru.evotor.devices.extra.SCANNED_CODE")?.trim() ?: return
        if (code.isEmpty() || code.length > 4000) return
        context.sendBroadcast(Intent(ACTION_INTERNAL_SCAN).setPackage(context.packageName).putExtra(EXTRA_CODE, code))
    }
    companion object {
        const val ACTION_INTERNAL_SCAN = "ru.sixthcup.evotor.INTERNAL_SCAN"
        const val EXTRA_CODE = "code"
    }
}
