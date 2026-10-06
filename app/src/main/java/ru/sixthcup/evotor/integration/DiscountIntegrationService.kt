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
import ru.evotor.framework.receipt.Receipt
import ru.evotor.framework.receipt.ReceiptApi
import ru.sixthcup.evotor.BuildConfig
import ru.sixthcup.evotor.data.CardSession
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Applies offline discount from signed QR token; always writes extras.sc for backend poll.
 * Short numeric code → kind=code, discount 0 (server accrues after SELL).
 * No call to 6.7 HTTPS from the terminal (TLS avoided).
 */
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
                        // invalid token — do not apply fake discount
                        callback.skip()
                        CardSession.clear(applicationContext)
                        return
                    }
                    val gross = receiptGross()
                    val voucherDiscount = card?.let { bestVoucherDiscount(it.vouchers, gross) } ?: BigDecimal.ZERO
                    val cashbackDiscount = card?.let {
                        BigDecimal(it.cashbackKopecks)
                            .divide(BigDecimal(100), 2, RoundingMode.DOWN)
                            .min(gross)
                    } ?: BigDecimal.ZERO
                    val totalDiscount = voucherDiscount.add(cashbackDiscount).min(gross).setScale(2, RoundingMode.DOWN)
                    val sc = JSONObject()
                        .put("v", 2)
                        .put("kid", BuildConfig.SERVER_KEY_ID)
                        .put("c", cardValue)
                        .put("kind", if (isShortCode) "code" else "token")
                        .put("op", UUID.randomUUID().toString())
                        .put("free", 0)
                        .put("cb", cashbackDiscount.multiply(BigDecimal(100)).setScale(0, RoundingMode.DOWN).toLong())
                        .put("disc", totalDiscount.toPlainString())
                        .put("ts", System.currentTimeMillis() / 1000)
                    callback.onResult(
                        ReceiptDiscountEventResult(
                            totalDiscount,
                            SetExtra(JSONObject().put("sc", sc)),
                            emptyList(),
                            null,
                        ),
                    )
                } catch (_: RemoteException) {
                    try { callback.skip() } catch (_: Exception) {}
                } catch (_: Throwable) {
                    try { callback.skip() } catch (_: Exception) {}
                } finally {
                    CardSession.clear(applicationContext)
                }
            }
        }
        return map
    }

    private fun receiptGross(): BigDecimal {
        return try {
            val receipt = ReceiptApi.getReceipt(this, Receipt.Type.SELL) ?: return BigDecimal.ZERO
            val positions = receipt.getPositions() ?: return BigDecimal.ZERO
            positions.fold(BigDecimal.ZERO) { acc, p ->
                acc.add(p.getTotalWithoutDiscounts() ?: BigDecimal.ZERO)
            }
        } catch (_: Throwable) {
            BigDecimal.ZERO
        }
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
                "percent" -> gross.multiply(BigDecimal(value)).divide(BigDecimal(100), 2, RoundingMode.DOWN)
                "fixed", "rub" -> BigDecimal(value).divide(BigDecimal(100), 2, RoundingMode.DOWN).min(gross)
                else -> BigDecimal.ZERO
            }
            if (candidate > best) best = candidate
        }
        return best.setScale(2, RoundingMode.DOWN)
    }
}
