package ru.sixthcup.evotor.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Единственный парсер/сериализатор добавок.
 * Каталог из сети ([ru.sixthcup.evotor.net.CatalogApi]) и локальный кэш ([CatalogCache]) обязаны
 * использовать этот код: иначе одна и та же опция читается с разными maxCount/ценой.
 */
object ToppingJson {

    /** Все 11 полей модели; `price` в JSON — алиас `priceRub`, отдельного поля в модели нет. */
    fun parse(a: JSONArray?): List<Topping> {
        if (a == null) return emptyList()
        val out = ArrayList<Topping>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(parseOne(o))
        }
        return out
    }

    fun parseOne(o: JSONObject): Topping = Topping(
        id = o.optInt("id"),
        name = o.optString("name"),
        priceRub = money(o, "priceRub", "price"),
        groupKey = o.optString("groupKey", "extra"),
        maxCount = o.optInt("maxCount", 1).coerceAtLeast(1),
        evotorUuid = o.optString("evotorUuid").ifBlank { null },
        required = o.optBoolean("required", false),
        isDefault = o.optBoolean("isDefault", false),
        zeroOption = o.optBoolean("zeroOption", false),
        available = if (o.has("available")) o.optBoolean("available", true) else true,
        productId = if (o.has("productId") && !o.isNull("productId")) o.optInt("productId") else null,
    )

    fun toJson(list: List<Topping>): JSONArray {
        val a = JSONArray()
        list.forEach { t ->
            a.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("priceRub", t.priceRub.toPlainString())
                    .put("groupKey", t.groupKey)
                    .put("maxCount", t.maxCount)
                    .put("evotorUuid", t.evotorUuid ?: JSONObject.NULL)
                    .put("required", t.required)
                    .put("isDefault", t.isDefault)
                    .put("zeroOption", t.zeroOption)
                    .put("available", t.available)
                    .put("productId", t.productId ?: JSONObject.NULL),
            )
        }
        return a
    }

    /** Цена приходит и числом (каталог), и строкой (кэш) — читаем оба варианта одинаково. */
    private fun money(o: JSONObject, vararg keys: String): BigDecimal {
        for (key in keys) {
            if (!o.has(key) || o.isNull(key)) continue
            val raw = o.optString(key, "").trim()
            if (raw.isEmpty()) continue
            return try {
                BigDecimal(raw)
            } catch (_: Throwable) {
                BigDecimal.valueOf(o.optDouble(key, 0.0))
            }
        }
        return BigDecimal.ZERO
    }
}
