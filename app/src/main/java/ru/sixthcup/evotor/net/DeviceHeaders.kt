package ru.sixthcup.evotor.net

import android.content.Context
import android.provider.Settings
import android.util.Log
import ru.sixthcup.evotor.SixthCupApp
import java.net.HttpURLConnection

/**
 * Общие заголовки кассы для backend: store + device.
 * Нужны catalog (уже отдельно), shift/timesheet, loyalty — чтобы не путать точки.
 */
object DeviceHeaders {
    private const val TAG = "DeviceHeaders"
    private const val PREF = "sixthcup_device"
    private const val KEY_DEV = "device_uuid"

    fun apply(conn: HttpURLConnection, context: Context? = SixthCupApp.instance) {
        val ctx = context ?: return
        try {
            StoreUuidResolver.peek(ctx)?.let {
                conn.setRequestProperty("X-Evotor-Store-Uuid", it)
            }
            deviceUuid(ctx)?.let {
                conn.setRequestProperty("X-Evotor-Device-UUID", it)
                conn.setRequestProperty("X-Evotor-Device-Uuid", it)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "apply headers failed: ${e.message}")
        }
    }

    fun deviceUuid(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEV, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Throwable) {
            null
        }?.takeIf { !it.isNullOrBlank() && it != "9774d56d682e549c" }
        if (androidId != null) {
            prefs.edit().putString(KEY_DEV, androidId).apply()
            return androidId
        }
        return null
    }
}
