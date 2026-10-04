package ru.sixthcup.evotor.data

import android.content.Context
import ru.sixthcup.evotor.BuildConfig

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sixthcup", Context.MODE_PRIVATE)

    var enrollCode: String
        get() = sp.getString("enroll", "") ?: ""
        set(v) = sp.edit().putString("enroll", v).apply()

    var deviceName: String
        get() = sp.getString("device_name", "Касса") ?: "Касса"
        set(v) = sp.edit().putString("device_name", v).apply()

    var deviceId: Int
        get() = sp.getInt("device_id", -1)
        set(v) = sp.edit().putInt("device_id", v).apply()

    var deviceToken: String
        get() = sp.getString("device_token", "") ?: ""
        set(v) = sp.edit().putString("device_token", v).apply()

    var publicKey: String
        get() = sp.getString("public_key", "") ?: ""
        set(v) = sp.edit().putString("public_key", v).apply()

    var storeName: String
        get() = sp.getString("store_name", "") ?: ""
        set(v) = sp.edit().putString("store_name", v).apply()

    var apiBaseUrl: String
        get() {
            if (!BuildConfig.DEBUG) return BuildConfig.API_BASE_URL
            return sp.getString("api_base", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.API_BASE_URL
        }
        set(v) {
            if (BuildConfig.DEBUG) sp.edit().putString("api_base", v.trimEnd('/')).apply()
        }

    var catalogJson: String
        get() = sp.getString("catalog_json", "") ?: ""
        set(v) = sp.edit().putString("catalog_json", v).apply()

    var pendingReceipts: String
        get() = sp.getString("pending_receipts", "[]") ?: "[]"
        set(v) = sp.edit().putString("pending_receipts", v).apply()

    var lastUserId: Int
        get() = sp.getInt("last_uid", -1)
        set(v) = sp.edit().putInt("last_uid", v).apply()

    var lastScanAt: Long
        get() = sp.getLong("last_scan_at", 0L)
        set(v) = sp.edit().putLong("last_scan_at", v).apply()

    var serverPub: String
        get() = sp.getString("server_pub", "") ?: ""
        set(v) = sp.edit().putString("server_pub", v).apply()

    var privateKey: String
        get() = sp.getString("private_key", "") ?: ""
        set(v) = sp.edit().putString("private_key", v).apply()

    var deviceRevoked: Boolean
        get() = sp.getBoolean("device_revoked", false)
        set(v) = sp.edit().putBoolean("device_revoked", v).apply()

    val isEnrolled: Boolean get() = deviceToken.isNotBlank()
}
