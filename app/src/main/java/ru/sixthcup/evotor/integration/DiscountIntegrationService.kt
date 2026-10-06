package ru.sixthcup.evotor.integration

import android.os.RemoteException
import org.json.JSONArray
import org.json.JSONObject
import ru.evotor.framework.core.IntegrationService
import ru.evotor.framework.core.action.event.receipt.changes.receipt.SetExtra
import ru.evotor.framework.core.action.event.receipt.discount.ReceiptDiscountEvent
import ru.evotor.framework.core.action.event.receipt.discount.ReceiptDiscountEventProcessor
import ru.evotor.framework.core.action.event.receipt.discount.ReceiptDiscountEventResult
import ru.evotor.framework.core.action.processor.ActionProcessor
import ru.evotor.framework.receipt.ReceiptApi
import ru.sixthcup.evotor.BuildConfig
import ru.sixthcup.evotor.data.CardSession
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

class DiscountIntegrationService : IntegrationService() {
    override fun createProcessors(): MutableMap<String, ActionProcessor>? {
        val map = HashMap<String, ActionProcessor>()
        map[ReceiptDiscountEvent.NAME_SELL_RECEIPT] = object : ReceiptDiscountEventProcessor() {
            override fun call(action: String, event: ReceiptDiscountEvent, callback: Callback) {
                try {
                    val cardValue = CardSession.get(applicationContext)
                    if (cardValue == null) {
                        callback.skip()
                        return
                    }
                    val isShortCode = cardValue.matches(Regex("\\d{1,18}"))
                    val card = if (isShortCode) null else CardTokenVerifier.verify(cardValue)
                    if (!isShortCode && card == null) {
                        callback.skip()
                        return
                    }

                    val receipt = ReceiptApi.getReceipt(applicationContext, event.receiptUuid)
                    val gross = receipt?.getPositions()?.fold(BigDecimal.ZERO) { acc, p ->
                        acc.add(p.getTotalWithoutDiscounts())
                    } ?: BigDecimal.ZERO

                    // Manual short-code mode deliberately does not calculate offline loyalty discounts.
                    // The server resolves the code after fiscal SELL and is the source of truth.
                    val voucherDiscount = card?.let { bestVoucherDiscount(it.vouchers, gross) } ?: BigDecimal.ZERO
                    val cashbackDiscount = card?.let {
                        BigDecimal(it.cashbackKopecks).divide(BigDecimal(100), 2, RoundingMode.DOWN).min(gross)
                    } ?: BigDecimal.ZERO
                    val totalDiscount = voucherDiscount.add(cashbackDiscount).min(gross).setScale(2, RoundingMode.DOWN)
                    val sc = JSONObject()
                        .put("v", 2)
                        .put("kid", BuildConfig.SERVER_KEY_ID)
                        .put("c", cardValue)
                        .put("kind", if (isShortCode) "code" else "token")
                        .put("op", UUID.randomUUID().toString())
                        .put("free", 0)
                        .put("cb", cashbackDiscount.multiply(BigDecimal(100)).setScale(0, RoundingMode.DOWN).longValueExact())
                        .put("disc", totalDiscount.toPlainString())
                        .put("ts", System.currentTimeMillis() / 1000)

                    callback.onResult(
                        ReceiptDiscountEventResult(
                            totalDiscount,
                            SetExtra(JSONObject().put("sc", sc)),
                            emptyList(),
                            null,
                        )
                    )
                } catch (_: RemoteException) {
                    try { callback.skip() } catch (_: Exception) {}
                } catch (_: Throwable) {
                    try { callback.skip() } catch (_: Exception) {}
                } finally {
                    // A scan belongs to one attempted discount operation only.
                    CardSession.clear(applicationContext)
                }
            }
        }
        return map
    }

    private fun bestVoucherDiscount(vouchers: JSONArray, gross: BigDecimal): BigDecimal {
        if (gross <= BigDecimal.ZERO) return BigDecimal.ZERO
        var best = BigDecimal.ZERO
        val today = System.currentTimeMillis() / 86_400_000L
        for (i in 0 until vouchers.length()) {
            val v = vouchers.optJSONArray(i) ?: continue
            val kind = v.optString(1)
            val value = v.optInt(2, 0)
            val expDay = v.optLong(3, 0)
            if (expDay < today || value <= 0) continue
            val candidate = when (kind) {
                "p" -> gross.multiply(BigDecimal(value)).divide(BigDecimal(100), 2, RoundingMode.DOWN)
                "f" -> BigDecimal(value).min(gross)
                else -> BigDecimal.ZERO
            }
            if (candidate > best) best = candidate
        }
        return best.setScale(2, RoundingMode.DOWN)
    }
}
