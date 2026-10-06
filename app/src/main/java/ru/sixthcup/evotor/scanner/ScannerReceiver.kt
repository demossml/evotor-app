package ru.sixthcup.evotor.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.sixthcup.evotor.integration.CardTokenVerifier
import ru.sixthcup.evotor.data.CardSession

class ScannerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("ru.evotor.devices.extra.SCANNED_CODE")
            ?: intent.getStringExtra("SCANNED_CODE")
            ?: intent.getStringExtra("code")
            ?: return

        val normalized = code.trim()
        if (!isAcceptedCardValue(normalized)) return
        CardSession.set(context, normalized)
        context.sendBroadcast(
            Intent(ACTION_INTERNAL_SCAN)
                .putExtra(EXTRA_CODE, normalized)
                .setPackage(context.packageName)
        )
    }

    private fun isAcceptedCardValue(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return false

        // Human card number: scanner may return exactly the same short numeric code
        // that the barista can type manually. Preserve leading zeroes in the session.
        if (value.matches(Regex("\\d{1,18}"))) return true

        // Preferred path: only a cryptographically valid signed customer QR is accepted.
        return CardTokenVerifier.verify(value) != null
    }

    companion object {
        const val ACTION_INTERNAL_SCAN = "ru.sixthcup.evotor.INTERNAL_SCAN"
        const val EXTRA_CODE = "code"
    }
}
