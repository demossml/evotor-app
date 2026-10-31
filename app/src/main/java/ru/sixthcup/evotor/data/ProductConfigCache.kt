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
        if (config.volumes.isEmpty() && config.milk == null && config.additions == null) return
        try {
            file(ctx, config.storeUuid, config.productId).writeText(toJson(config).toString())
        } catch (e: Throwable) {
            Log.w(TAG, "config_cache_save_failed product=${config.productId}", e)
        }
    }

    fun load(ctx: Context, storeUuid: String, productId: Int): Pair<ProductConfig, Long>? = try {
        val f = file(ctx, storeUuid, productId)
        if (!f.isFile || f.length() == 0L) null else {
            val root = JSONObject(f.readText())
            val savedAt = root.optLong("savedAt", 0L)
            // Через тот же парсер: кэш и сеть читаются одинаково.
            val parsed = ProductConfigApi.parse(root.getJSONObject("config").toString(), productId, storeUuid).config
            if (parsed == null) null else parsed to savedAt
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
                    .put("available", v.available),
            )
        }
        val cfg = JSONObject()
            .put("ok", true)
            .put("schemaVersion", c.schemaVersion)
            .put("storeUuid", c.storeUuid)
            .put("productId", c.productId)
            .put("volumes", volumes)

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
                            .put("available", o.available),
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
                            .put("sendToReceipt", o.sendToReceipt),
                    )
                }
                byProduct.put(
                    pid.toString(),
                    JSONObject().put("schemeId", scheme.schemeId ?: JSONObject.NULL).put("options", options),
                )
            }
            cfg.put("additions", JSONObject().put("required", a.required).put("byProductId", byProduct))
        }

        return JSONObject().put("savedAt", System.currentTimeMillis()).put("config", cfg)
    }
}
