package ru.sixthcup.evotor.integration

import android.os.RemoteException
import android.util.Log
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
import ru.sixthcup.evotor.data.CheckoutChoice
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.DiscountCalc
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
                        // Без карты гостя остаётся только выгода комбо — она не требует резерва.
                        val comboOnly = CheckoutChoice.comboRub(applicationContext)
                        if (comboOnly.signum() > 0) {
                            val grossNoCard = receiptUnitPrices().fold(BigDecimal.ZERO) { a, p -> a.add(p.second) }
                            if (grossNoCard.signum() > 0) {
                                callback.onResult(
                                    ReceiptDiscountEventResult(comboOnly.min(grossNoCard), null, emptyList(), null),
                                )
                                return
                            }
                        }
                        callback.skip()
                        return
                    }
                    val now = System.currentTimeMillis() / 1000
                    val reserved = DiscountCalc.reserved(info, now)
                    val prices = receiptUnitPrices()
                    val gross = prices.fold(BigDecimal.ZERO) { a, p -> a.add(p.second) }
                    // Free cup: the cheapest single unit on the receipt (the backend still re-checks it).
                    val eligibleUnitPrices = Cart.all().filter { it.freeEligible }.map { it.priceRub }
                    val requestedCb = if (CheckoutChoice.cashback(applicationContext)) CheckoutChoice.cashbackKopecks(applicationContext) else 0L
                    val calc = DiscountCalc.compute(gross, eligibleUnitPrices, reserved, CheckoutChoice.free(applicationContext), info.freeAvailable, requestedCb, info.cashbackKopecks)
                    val freeDiscount = calc.free
                    val cbRub = calc.cashback
                    // Скидка комбо — фиксированная выгода набора (позиции в чеке реальные, цена полная).
                    val comboRub = CheckoutChoice.comboRub(applicationContext)
                    val total = calc.total.add(comboRub).min(gross)
                    val cbKopecks = cbRub.multiply(BigDecimal(100)).setScale(0, RoundingMode.DOWN).toLong()
                    val freeApplied = freeDiscount.signum() > 0
                    Log.i(
                        "DiscountIntegration",
                        "extras_written v=2 hasOp=${info.reservationId != null} free=${if (freeApplied) 1 else 0} cb=$cbKopecks",
                    )
                    callback.onResult(
                        ReceiptDiscountEventResult(
                            total,
                            SetExtra(CardSession.extras(info, freeApplied, cbKopecks)),
                            emptyList(),
                            null,
                        ),
                    )
                } catch (_: Throwable) {
                    try { callback.skip() } catch (_: Exception) {}
                } finally {
                    CheckoutChoice.clear(applicationContext)
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

}
