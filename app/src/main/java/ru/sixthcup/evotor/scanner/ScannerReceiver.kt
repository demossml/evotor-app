package ru.sixthcup.evotor.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Official Evotor path (integration-library ScannerBroadcastReceiver / BarcodeReceivedEvent):
 * action = "ru.evotor.devices.ScannedCode"
 * barcode string key in extras = **"ScannedCode"** (NOT ru.evotor.devices.extra.SCANNED_CODE).
 *
 * Old builds read the wrong extra → null → silent return while the scanner still beeps.
 */
class ScannerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCANNED && intent.action != ACTION_INTERNAL_SCAN) return
        val code = extractCode(intent)?.trim().orEmpty()
        if (code.isEmpty() || code.length > 4000) {
            Log.w(TAG, "scan ignored action=${intent.action} extras=${intent.extras?.keySet()}")
            return
        }
        Log.i(TAG, "scan ok len=${code.length} action=${intent.action}")
        ScannerHub.offer(code)
        // Deliver to Activity even if it only listens for INTERNAL_SCAN.
        context.sendBroadcast(
            Intent(ACTION_INTERNAL_SCAN)
                .setPackage(context.packageName)
                .putExtra(EXTRA_CODE, code)
        )
    }

    companion object {
        private const val TAG = "SC_SCAN"
        const val ACTION_SCANNED = "ru.evotor.devices.ScannedCode"
        const val ACTION_INTERNAL_SCAN = "ru.sixthcup.evotor.INTERNAL_SCAN"
        const val EXTRA_CODE = "code"

        /** Same key as ru.evotor.framework.device.scanner.event.BarcodeReceivedEvent.KEY_BARCODE */
        private const val KEY_OFFICIAL = "ScannedCode"
        private val FALLBACK_KEYS = arrayOf(
            "ru.evotor.devices.extra.SCANNED_CODE",
            "barcode",
            "data",
            "com.symbol.datawedge.data_string",
            EXTRA_CODE,
        )

        fun extractCode(intent: Intent): String? {
            // 1) Official Evotor bundle key
            intent.extras?.getString(KEY_OFFICIAL)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            // 2) Fallbacks (legacy / other firmwares)
            for (k in FALLBACK_KEYS) {
                intent.getStringExtra(k)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            // 3) Any string extra that looks non-empty (last resort, skip huge blobs)
            val b: Bundle = intent.extras ?: return null
            for (k in b.keySet()) {
                val v = b.get(k) as? String ?: continue
                val t = v.trim()
                if (t.isNotEmpty() && t.length <= 4000) return t
            }
            return null
        }
    }
}
