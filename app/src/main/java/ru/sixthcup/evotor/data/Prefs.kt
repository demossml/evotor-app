package ru.sixthcup.evotor.data

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sixthcup", Context.MODE_PRIVATE)

    var enrollCode: String
        get() = sp.getString("enroll", "") ?: ""
        set(v) = sp.edit().putString("enroll", v).apply()

    var deviceName: String
        get() = sp.getString("device_name", "Касса") ?: "Касса"
        set(v) = sp.edit().putString("device_name", v).apply()

    var lastUserId: Int
        get() = sp.getInt("last_uid", -1)
        set(v) = sp.edit().putInt("last_uid", v).apply()

    var lastScanAt: Long
        get() = sp.getLong("last_scan_at", 0L)
        set(v) = sp.edit().putLong("last_scan_at", v).apply()
}
