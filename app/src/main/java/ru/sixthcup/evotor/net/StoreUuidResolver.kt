package ru.sixthcup.evotor.net

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log
import ru.sixthcup.evotor.BuildConfig

/**
 * UUID магазина (точки) текущего терминала.
 *
 * Нужен, чтобы `GET /api/devices/catalog?storeUuid=…` отдавал товары именно этого магазина:
 * без параметра backend возвращает связки другой точки, `evotorUuid` не совпадают
 * и обогащение (фото, добавки, скрытие модификаторов) не применяется.
 *
 * Источники по порядку:
 *  1. Значение, сохранённое при прошлом успешном разрешении.
 *  2. `BuildConfig.STORE_UUID` — если магазин зафиксирован при сборке (`-PSTORE_UUID=…`).
 *  3. `Settings.Secure` — ключи, которыми Эвотор может публиковать uuid точки.
 *  4. ContentProvider-провайдеры Эвотор: перебираем известные authority/пути и ищем UUID в ответе.
 *
 * Если ничего не найдено — null: запрос уходит без storeUuid (поведение как раньше),
 * но это фиксируется в логе.
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

    /** authority → пути, которые имеет смысл опросить. */
    private val PROVIDERS = listOf(
        "ru.evotor.evotorpos.settings" to listOf("store", "stores", "shop", "company", "device", "settings"),
        "ru.evotor.evotorpos" to listOf("store", "stores", "company", "device"),
        "ru.evotor.provider" to listOf("store", "stores", "device"),
    )

    fun resolve(context: Context): String? {
        cached(context)?.let { Log.i(TAG, "resolved=$it (cache)"); return it }

        val fromBuild = BuildConfig.STORE_UUID.trim()
        if (fromBuild.isNotEmpty() && UUID_RE.matches(fromBuild)) {
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

        Log.w(TAG, "resolved=null — catalog will be requested without storeUuid")
        return null
    }

    /** Запомнить uuid, полученный из другого источника (например, если backend вернул его в ответе). */
    fun remember(context: Context, uuid: String?) {
        val v = uuid?.trim().orEmpty()
        if (UUID_RE.matches(v)) {
            save(context, v)
            Log.i(TAG, "remembered=$v")
        }
    }

    private fun cached(context: Context): String? = context
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(KEY, null)
        ?.takeIf { UUID_RE.matches(it) }

    private fun save(context: Context, uuid: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, uuid).apply()
    }

    private fun fromSecureSettings(context: Context): String? {
        for (key in SECURE_KEYS) {
            val raw = try {
                Settings.Secure.getString(context.contentResolver, key)
                    ?: Settings.Global.getString(context.contentResolver, key)
            } catch (_: Throwable) {
                null
            } ?: continue
            UUID_RE.find(raw)?.let { return it.value }
        }
        return null
    }

    private fun fromProviders(context: Context): String? {
        for ((authority, paths) in PROVIDERS) {
            for (path in paths) {
                val uri = Uri.parse("content://$authority/$path")
                val found = try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        var result: String? = null
                        val scan = minOf(c.columnCount, 24)
                        while (c.moveToNext() && result == null) {
                            for (i in 0 until scan) {
                                val v = try { c.getString(i) } catch (_: Throwable) { null } ?: continue
                                if (UUID_RE.matches(v.trim())) { result = v.trim(); break }
                                UUID_RE.find(v)?.let { result = it.value }
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
}
