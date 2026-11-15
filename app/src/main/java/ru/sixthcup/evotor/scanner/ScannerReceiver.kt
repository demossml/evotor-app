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
            val b: Bundle = intent.extras ?: return null
            val values = HashMap<String, String?>(b.size())
            for (k in b.keySet()) values[k] = b.getString(k)
            val code = codeFromStrings(values)
            if (code == null && values.keys.any { it !in KNOWN_KEYS }) {
                Log.d(TAG, "scan_ignored_unknown_extras keys=${values.keys}")
            }
            return code
        }

        /** Только официальный ключ и известные legacy-ключи; любые прочие extras игнорируются. */
        internal fun codeFromStrings(values: Map<String, String?>): String? {
            for (k in KNOWN_KEYS) {
                val t = values[k]?.trim().orEmpty()
                if (t.isNotEmpty() && t.length <= 4000) return t
            }
            return null
        }

        private val KNOWN_KEYS: List<String> = listOf(KEY_OFFICIAL) + FALLBACK_KEYS
    }
}
