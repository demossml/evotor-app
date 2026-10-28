package ru.sixthcup.evotor.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Ваучер из ответа `resolve` (backend отдаёт `vouchers[]`).
 * Отсутствие поля — норма: касса работает как раньше.
 */
data class ResolveVoucher(
    val id: Int,
    val title: String,
    /** `percent` — процент от остатка после подарка, `fixed` — сумма в копейках. */
    val kind: String,
    /** percent: 1..100, fixed: копейки (30000 = 300 ₽). */
    val value: Int,
    val redemptionMode: String = "once",
    val stackableWithFreeCup: Boolean = true,
    val expiresDay: Int? = null,
    /** Картинка для витрины; логику не участвует. */
    val imageUrl: String? = null,
) {
    val isPercent: Boolean get() = kind.equals("percent", true)
}

object VoucherJson {
    fun toJson(list: List<ResolveVoucher>): JSONArray {
        val arr = JSONArray()
        list.forEach { v ->
            arr.put(
                JSONObject()
                    .put("id", v.id).put("title", v.title).put("kind", v.kind).put("value", v.value)
                    .put("redemptionMode", v.redemptionMode).put("stackableWithFreeCup", v.stackableWithFreeCup)
                    .put("expiresDay", v.expiresDay ?: JSONObject.NULL)
                    .put("imageUrl", v.imageUrl ?: JSONObject.NULL),
            )
        }
        return arr
    }

    /** Толерантный парсер: мусор и отсутствие поля дают пустой список, а не исключение. */
    fun parse(arr: JSONArray?): List<ResolveVoucher> {
        if (arr == null) return emptyList()
        val out = ArrayList<ResolveVoucher>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0)
            val title = o.optString("title").trim()
            val kind = o.optString("kind").trim()
            val value = o.optInt("value", 0)
            if (id <= 0 || title.isEmpty() || value <= 0) continue
            if (kind != "percent" && kind != "fixed") continue
            out.add(
                ResolveVoucher(
                    id = id,
                    title = title,
                    kind = kind,
                    value = value,
                    redemptionMode = o.optString("redemptionMode").ifBlank { "once" },
                    stackableWithFreeCup = if (o.has("stackableWithFreeCup")) o.optBoolean("stackableWithFreeCup", true) else true,
                    expiresDay = if (o.has("expiresDay") && !o.isNull("expiresDay")) o.optInt("expiresDay") else null,
                    imageUrl = o.optString("imageUrl").trim().ifEmpty { null },
                ),
            )
        }
        return out
    }
}

/**
 * Скидка по ваучеру. Правило зафиксировано: процент считается **от остатка после подарка**,
 * фиксированная сумма ограничена этим же остатком. Суммарная скидка не превышает чек.
 */
object VoucherCalc {
    fun discount(grossAfterFree: BigDecimal, voucher: ResolveVoucher?): BigDecimal =
        DiscountCalc.voucherDiscount(grossAfterFree, voucher)

    /** Можно ли применить ваучер вместе с уже выбранным подарком. */
    fun allowedTogether(voucher: ResolveVoucher?, freeChosen: Boolean): Boolean =
        voucher == null || !freeChosen || voucher.stackableWithFreeCup

    /** Размер скидки для кассира: `15%` или `300 ₽` (fixed приходит в копейках). */
    fun amountLabel(voucher: ResolveVoucher): String =
        if (voucher.isPercent) "${voucher.value}%"
        else BigDecimal(voucher.value).divide(BigDecimal(100)).stripTrailingZeros().toPlainString() + " ₽"

    /** Подпись строки ваучера: «Скидка 15% на кофе −15%». */
    fun label(voucher: ResolveVoucher): String = "${voucher.title} −${amountLabel(voucher)}"
}
