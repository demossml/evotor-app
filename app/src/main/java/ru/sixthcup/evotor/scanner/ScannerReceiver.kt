package ru.sixthcup.evotor.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives barcode/QR from Evotor terminal scanner.
 * Official action: ru.evotor.devices.ScannedCode
 * Docs: https://developer.evotor.ru/docs/doc_java_barcode_scanner.html
 *
 * On real terminal prefer extending ru.evotor.devices.Scanners.ScannerBroadcastReceiver
 * from integration-library when available on device classpath.
 */
class ScannerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("barcode")
            ?: intent.getStringExtra("EXTRA_SCANNED_CODE")
            ?: intent.getStringExtra("ru.evotor.devices.extra.BARCODE")
            ?: intent.extras?.keySet()?.firstNotNullOfOrNull { k ->
                intent.extras?.get(k)?.toString()?.takeIf { it.length > 8 }
            }
        if (!code.isNullOrBlank()) {
            Log.d(TAG, "scanned len=${code.length}")
            context.sendBroadcast(
                Intent(ACTION_INTERNAL_SCAN).setPackage(context.packageName).putExtra(EXTRA_CODE, code)
            )
        }
    }

    companion object {
        private const val TAG = "SixthCupScan"
        const val ACTION_INTERNAL_SCAN = "ru.sixthcup.evotor.INTERNAL_SCAN"
        const val EXTRA_CODE = "code"
    }
}
