package ru.sixthcup.evotor.net

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * UUID магазина (точки) текущего терминала.
 *
 * Нужен, чтобы catalog/shift/loyalty ходили в backend с uuid ЭТОЙ точки:
 * иначе при 2–3 кассах приходят чужие product uuid и merge ломается.
 *
 * Источники по порядку:
 *  1. Prefs (запомненный).
 *  2. BuildConfig.STORE_UUID (`-PSTORE_UUID=…` при сборке на точку).
 *  3. Settings.Secure / Global.
 *  4. ContentProvider Эвотор.
 *  5. GET /api/devices/whoami (backend) с device header.
 */
object StoreUuidResolver {
    private const val TAG = "StoreUuid"
    private const val PREF = "sixthcup_store"
    private const val KEY = "store_uuid"

    private val UUID_RE = Regex(
        "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}",
    )

    private val SECURE_KEYS = listOf(
        "evotor_store_uuid",
        "store_uuid",
        "evotor_shop_uuid",
        "shop_uuid",
        "ru.evotor.store.uuid",
    )

    private val PROVIDERS = listOf(
        "ru.evotor.evotorpos.settings" to listOf("store", "stores", "shop", "company", "device", "settings"),
        "ru.evotor.evotorpos" to listOf("store", "stores", "company", "device"),
        "ru.evotor.provider" to listOf("store", "stores", "device"),
    )

    /**
     * UUID точки продаж. APP_UUID приложения (manifest `app_uuid`) — не точка:
     * отклоняется из любого источника, включая уже сохранённый кэш.
     */
    internal fun isStoreUuid(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        return UUID_RE.matches(v) && !v.equals(BuildConfig.APP_UUID, ignoreCase = true)
    }

    /** Только чтение кэша, без side-effects (для HTTP-заголовков). */
    fun peek(context: Context): String? = cached(context)

    fun resolve(context: Context): String? {
        cached(context)?.let { Log.i(TAG, "resolved=$it (cache)"); return it }

        val fromBuild = BuildConfig.STORE_UUID.trim()
        if (isStoreUuid(fromBuild)) {
            save(context, fromBuild)
            Log.i(TAG, "resolved=$fromBuild (build)")
            return fromBuild
        }

        fromSecureSettings(context)?.let {
            save(context, it)
            Log.i(TAG, "resolved=$it (secure settings)")
            return it
        }

        fromProviders(context)?.let {
            save(context, it)
            Log.i(TAG, "resolved=$it (evotor provider)")
            return it
        }

        fromWhoami(context)?.let {
            save(context, it)
            Log.i(TAG, "resolved=$it (whoami)")
            return it
        }

        Log.w(TAG, "resolved=null — catalog may use wrong store uuids on multi-store")
        return null
    }

    fun remember(context: Context, uuid: String?) {
        val v = uuid?.trim().orEmpty()
        if (isStoreUuid(v)) {
            save(context, v)
            Log.i(TAG, "remembered=$v")
        }
    }

    private fun cached(context: Context): String? = context
        .applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(KEY, null)
        ?.takeIf { isStoreUuid(it) }

    private fun save(context: Context, uuid: String) {
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, uuid).apply()
    }

    private fun fromSecureSettings(context: Context): String? {
        for (key in SECURE_KEYS) {
            val raw = try {
                Settings.Secure.getString(context.contentResolver, key)
                    ?: Settings.Global.getString(context.contentResolver, key)
            } catch (_: Throwable) {
                null
            } ?: continue
            UUID_RE.find(raw)?.value?.takeIf { isStoreUuid(it) }?.let { return it }
        }
        return null
    }

    private fun fromProviders(context: Context): String? {
        for ((authority, paths) in PROVIDERS) {
            for (path in paths) {
                val uri = Uri.parse("content://$authority/$path")
                val found = try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        // Только колонки «store/shop»: любой UUID подряд (app/device/user) — не точка.
                        val cols = (0 until minOf(c.columnCount, 24)).filter { i ->
                            val n = c.getColumnName(i).orEmpty().lowercase()
                            "store" in n || "shop" in n
                        }
                        var result: String? = null
                        while (c.moveToNext() && result == null) {
                            for (i in cols) {
                                val v = try { c.getString(i) } catch (_: Throwable) { null } ?: continue
                                result = UUID_RE.find(v)?.value?.takeIf { isStoreUuid(it) }
                                if (result != null) break
                            }
                        }
                        result
                    }
                } catch (_: Throwable) {
                    null
                }
                if (found != null) return found
            }
        }
        return null
    }

    /** Backend helper — only when local sources failed. Blocking; call off main thread. */
    private fun fromWhoami(context: Context): String? {
        return try {
            val url = BuildConfig.API_BASE_URL.trimEnd('/') + "/api/devices/whoami"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.setRequestProperty("Accept", "application/json")
            DeviceHeaders.apply(conn, context)
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (status !in 200..299) {
                Log.w(TAG, "whoami HTTP $status")
                return null
            }
            val o = JSONObject(body)
            val s = o.optString("storeUuid").trim()
            if (isStoreUuid(s)) s else null
        } catch (e: Throwable) {
            Log.w(TAG, "whoami failed: ${e.message}")
            null
        }
    }
}
