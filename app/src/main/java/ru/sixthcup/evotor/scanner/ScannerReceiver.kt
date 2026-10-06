package ru.sixthcup.evotor.scanner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.sixthcup.evotor.data.CardSession

class ScannerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("ru.evotor.devices.extra.SCANNED_CODE")
            ?: intent.getStringExtra("SCANNED_CODE")
            ?: intent.getStringExtra("code")
            ?: return
        CardSession.set(context, code)
        context.sendBroadcast(
            Intent(ACTION_INTERNAL_SCAN).putExtra(EXTRA_CODE, code).setPackage(context.packageName),
        )
    }
    companion object {
        const val ACTION_INTERNAL_SCAN = "ru.sixthcup.evotor.INTERNAL_SCAN"
        const val EXTRA_CODE = "code"
    }
}
