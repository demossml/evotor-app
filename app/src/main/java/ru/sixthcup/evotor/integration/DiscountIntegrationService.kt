package ru.sixthcup.evotor.integration

import android.os.RemoteException
import org.json.JSONObject
import ru.evotor.framework.core.IntegrationService
import ru.evotor.framework.core.action.event.receipt.changes.receipt.SetExtra
import ru.evotor.framework.core.action.event.receipt.discount.ReceiptDiscountEvent
import ru.evotor.framework.core.action.event.receipt.discount.ReceiptDiscountEventProcessor
import ru.evotor.framework.core.action.event.receipt.discount.ReceiptDiscountEventResult
import ru.evotor.framework.core.action.processor.ActionProcessor
import ru.evotor.framework.receipt.Receipt
import ru.evotor.framework.receipt.ReceiptApi
import ru.sixthcup.evotor.data.CardSession
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Applies the discount for the bonus that the backend RESERVED when the QR was scanned
 * (free cup and/or cashback) and writes extras.sc for the backend to read after the SELL.
 * The terminal never decides balances: if there is no reservation, no discount is given.
 */
class DiscountIntegrationService : IntegrationService() {
    override fun createProcessors(): MutableMap<String, ActionProcessor>? {
        val map = HashMap<String, ActionProcessor>()
        map[ReceiptDiscountEvent.NAME_SELL_RECEIPT] = object : ReceiptDiscountEventProcessor() {
            override fun call(action: String, event: ReceiptDiscountEvent, callback: Callback) {
                try {
                    val info = CardSession.get(applicationContext)
                    if (info == null || info.code.isEmpty()) {
                        callback.skip()
                        return
                    }
                    val now = System.currentTimeMillis() / 1000
                    val reserved = info.reservationId != null && (info.reservationExpiresAt == 0L || info.reservationExpiresAt + GRACE_SEC >= now)
                    val prices = receiptUnitPrices()
                    val gross = prices.fold(BigDecimal.ZERO) { a, p -> a.add(p.second) }
                    // Free cup: the cheapest single unit on the receipt (the backend still re-checks it).
                    val freeDiscount = if (reserved && info.freeAvailable > 0 && prices.isNotEmpty())
                        prices.minOf { it.first }.setScale(2, RoundingMode.DOWN) else BigDecimal.ZERO
                    val cbRub = if (reserved)
                        BigDecimal(info.cashbackKopecks).divide(BigDecimal(100), 2, RoundingMode.DOWN).min(gross.subtract(freeDiscount).max(BigDecimal.ZERO))
                    else BigDecimal.ZERO
                    val total = freeDiscount.add(cbRub).min(gross).setScale(2, RoundingMode.DOWN)
                    val sc = CardSession.sc(info, freeDiscount.signum() > 0, cbRub.multiply(BigDecimal(100)).setScale(0, RoundingMode.DOWN).toLong())
                    callback.onResult(
                        ReceiptDiscountEventResult(total, SetExtra(JSONObject().put("sc", sc)), emptyList(), null),
                    )
                } catch (_: Throwable) {
                    try { callback.skip() } catch (_: Exception) {}
                } finally {
                    // One scan serves exactly one sale.
                    CardSession.clear(applicationContext)
                }
            }
        }
        return map
    }

    /** (unit price, line total) for every position of the open sell receipt. */
    private fun receiptUnitPrices(): List<Pair<BigDecimal, BigDecimal>> {
        return try {
            val receipt = ReceiptApi.getReceipt(this, Receipt.Type.SELL) ?: return emptyList()
            (receipt.getPositions() ?: return emptyList()).map { p ->
                val total = p.getTotalWithoutDiscounts() ?: BigDecimal.ZERO
                val qty = p.getQuantity()?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
                Pair(total.divide(qty, 2, RoundingMode.DOWN), total)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private companion object {
        /** Slack for a slow barista: the backend honours a lapsed reservation only if nobody else took the bonus. */
        const val GRACE_SEC = 300L
    }
}
