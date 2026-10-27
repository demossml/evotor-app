package ru.sixthcup.evotor.data

import android.content.Context
import org.json.JSONObject

/**
 * Что печатать про лояльность на чеке текущей продажи.
 *
 * Снимок делается до оплаты (когда известны карта, прогресс и решение по награде), а читается
 * в момент печати фискального чека — PrintExtra срабатывает уже после оплаты, спросить сервер
 * в этот момент нельзя. Персональных данных здесь нет: только прогресс и факты по чеку.
 */
data class SalePrintLoyalty(
    val cardFound: Boolean,
    val progressCurrent: Int,
    val progressTarget: Int,
    /** Есть ли доверенные данные прогресса (для ручного кода сервер их скрывает). */
    val progressKnown: Boolean,
    val cupsEarnedThisSale: Int = 0,
    val rewardApplied: Boolean = false,
    val rewardTitle: String? = null,
) {
    /** Строки для чека. Пусто — по лояльности печатать нечего. */
    fun lines(): List<String> {
        if (!cardFound) return emptyList()
        val out = ArrayList<String>(3)
        if (progressKnown && progressTarget > 0) {
            out += "Лояльность: $progressCurrent из $progressTarget"
        }
        if (cupsEarnedThisSale > 0) out += "Начислено: +$cupsEarnedThisSale"
        if (rewardApplied) out += rewardTitle?.takeIf { it.isNotBlank() } ?: "Награда применена"
        if (out.isEmpty()) out += "Лояльность: карта принята"
        return out
    }
}

/** Снимок лояльности «под текущую продажу»: переживает до момента печати чека. */
object SalePrintStore {
    private const val PREF = "sixthcup_sale_print"
    private const val KEY_LOYALTY = "loyalty"

    fun save(ctx: Context, loyalty: SalePrintLoyalty) {
        val o = JSONObject()
            .put("cardFound", loyalty.cardFound)
            .put("current", loyalty.progressCurrent)
            .put("target", loyalty.progressTarget)
            .put("known", loyalty.progressKnown)
            .put("earned", loyalty.cupsEarnedThisSale)
            .put("reward", loyalty.rewardApplied)
            .put("title", loyalty.rewardTitle ?: JSONObject.NULL)
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOYALTY, o.toString()).apply()
    }

    fun peek(ctx: Context): SalePrintLoyalty? {
        return try {
            val s = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LOYALTY, null)
            if (s.isNullOrBlank()) null
            else {
                val o = JSONObject(s)
                SalePrintLoyalty(
                    cardFound = o.optBoolean("cardFound", false),
                    progressCurrent = o.optInt("current", 0),
                    progressTarget = o.optInt("target", 0),
                    progressKnown = o.optBoolean("known", false),
                    cupsEarnedThisSale = o.optInt("earned", 0),
                    rewardApplied = o.optBoolean("reward", false),
                    rewardTitle = if (o.isNull("title")) null else o.optString("title").takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_LOYALTY).apply()
    }
}

/** Итоговый текст на чеке: номер заказа + блок лояльности. */
object ReceiptPrintText {
    fun lines(queue: QueueNumber?, loyalty: SalePrintLoyalty?): List<String> {
        val out = ArrayList<String>(4)
        if (QueueNumber.isUsable(queue)) out += QueueNumber.printTextFor(queue!!.publicNumber)
        loyalty?.lines()?.let { out += it }
        return out
    }
}
