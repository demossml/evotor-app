package ru.sixthcup.evotor.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.net.ProductConfigApi
import java.io.File

/**
 * Кэш последней достоверной конфигурации товара: ключ `storeUuid + productId`.
 *
 * Нужен, когда сервер недоступен: касса показывает сохранённые данные и **не обходит**
 * обязательную схему молока. Пустой или ошибочный ответ кэш не перезаписывает.
 */
object ProductConfigCache {
    private const val TAG = "ProductConfigCache"

    private fun file(ctx: Context, storeUuid: String, productId: Int): File {
        val safeStore = storeUuid.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(ctx.applicationContext.filesDir, "product_config_${safeStore}_$productId.json")
    }

    fun save(ctx: Context, config: ProductConfig) {
        if (config.volumes.isEmpty() && config.milk == null && config.additions == null && config.choice == null) return
        try {
            file(ctx, config.storeUuid, config.productId).writeText(toJson(config).toString())
        } catch (e: Throwable) {
            Log.w(TAG, "config_cache_save_failed product=${config.productId}", e)
        }
    }

    /** Сутки: более старая запись не считается достоверной — схемы на сервере могли измениться. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Годна ли запись на момент [now]. Старое или битое время — не годна. */
    fun isFresh(savedAt: Long, now: Long): Boolean = savedAt > 0L && now - savedAt <= MAX_AGE_MS

    fun load(
        ctx: Context,
        storeUuid: String,
        productId: Int,
        now: Long = System.currentTimeMillis(),
    ): Pair<ProductConfig, Long>? = try {
        val f = file(ctx, storeUuid, productId)
        if (!f.isFile || f.length() == 0L) null else {
            val root = JSONObject(f.readText())
            val savedAt = root.optLong("savedAt", 0L)
            // Через тот же парсер: кэш и сеть читаются одинаково (productId/storeUuid сверяются).
            val parsed = ProductConfigApi.parse(root.getJSONObject("config").toString(), productId, storeUuid).config
            when {
                parsed == null -> null
                !isFresh(savedAt, now) -> {
                    Log.i(TAG, "config_cache_stale product=$productId ageMs=${now - savedAt}")
                    null
                }
                else -> parsed to savedAt
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "config_cache_load_failed product=$productId", e)
        null
    }

    /** Сериализация конфигурации — без секретов, только то, что пришло с сервера. */
    fun toJson(c: ProductConfig): JSONObject {
        val volumes = JSONArray()
        c.volumes.forEach { v ->
            volumes.put(
                JSONObject()
                    .put("productId", v.productId)
                    .put("evotorUuid", v.evotorUuid ?: JSONObject.NULL)
                    .put("name", v.name)
                    .put("label", v.label)
                    .put("sizeValue", v.sizeValue ?: JSONObject.NULL)
                    .put("sizeUnit", v.sizeUnit ?: JSONObject.NULL)
                    .put("priceRub", v.priceRub?.toPlainString() ?: JSONObject.NULL)
                    .put("available", v.available)
                    .put("notReadyReason", v.notReadyReason ?: JSONObject.NULL),
            )
        }
        val cfg = JSONObject()
            .put("ok", true)
            .put("schemaVersion", c.schemaVersion)
            .put("storeUuid", c.storeUuid)
            .put("productId", c.productId)
            .put("volumes", volumes)
            .put("configErrors", JSONArray(c.configErrors))

        c.milk?.let { m ->
            val byProduct = JSONObject()
            m.byProductId.forEach { (pid, scheme) ->
                val options = JSONArray()
                scheme.options.forEach { o ->
                    options.put(
                        JSONObject()
                            .put("id", o.id)
                            .put("productId", o.productId ?: JSONObject.NULL)
                            .put("label", o.label)
                            .put("evotorUuid", o.evotorUuid ?: JSONObject.NULL)
                            .put("isFree", o.isFree)
                            .put("isDefault", o.isDefault)
                            .put("sendToReceipt", o.sendToReceipt)
                            .put("available", o.available)
                            .put("notReadyReason", o.notReadyReason ?: JSONObject.NULL),
                    )
                }
                byProduct.put(
                    pid.toString(),
                    JSONObject().put("schemeId", scheme.schemeId ?: JSONObject.NULL).put("options", options),
                )
            }
            cfg.put("milk", JSONObject().put("required", m.required).put("byProductId", byProduct))
        }

        c.additions?.let { a ->
            val byProduct = JSONObject()
            a.byProductId.forEach { (pid, scheme) ->
                val options = JSONArray()
                scheme.options.forEach { o ->
                    options.put(
                        JSONObject()
                            .put("id", o.id)
                            .put("productId", o.productId ?: JSONObject.NULL)
                            .put("name", o.name)
                            .put("groupKey", o.groupKey)
                            .put("evotorUuid", o.evotorUuid ?: JSONObject.NULL)
                            .put("priceRub", o.priceRub.toPlainString())
                            .put("required", o.required)
                            .put("maxCount", o.maxCount)
                            .put("isDefault", o.isDefault)
                            .put("zeroOption", o.zeroOption)
                            .put("available", o.available)
                            .put("sendToReceipt", o.sendToReceipt)
                            .put("notReadyReason", o.notReadyReason ?: JSONObject.NULL),
                    )
                }
                byProduct.put(
                    pid.toString(),
                    JSONObject().put("schemeId", scheme.schemeId ?: JSONObject.NULL).put("options", options),
                )
            }
            cfg.put("additions", JSONObject().put("required", a.required).put("byProductId", byProduct))
        }

        c.choice?.let { ch ->
            val byProduct = JSONObject()
            ch.byProductId.forEach { (pid, scheme) ->
                val options = JSONArray()
                scheme.options.forEach { o ->
                    options.put(JSONObject().put("id", o.id).put("label", o.label).put("isDefault", o.isDefault))
                }
                val entry = JSONObject()
                    .put("schemeId", scheme.schemeId ?: JSONObject.NULL)
                    .put("title", scheme.title)
                    .put("options", options)
                if (scheme.invalid) entry.put("error", "invalid_scheme")
                byProduct.put(pid.toString(), entry)
            }
            cfg.put("choices", JSONObject().put("required", ch.required).put("byProductId", byProduct))
        }

        return JSONObject().put("savedAt", System.currentTimeMillis()).put("config", cfg)
    }
}
