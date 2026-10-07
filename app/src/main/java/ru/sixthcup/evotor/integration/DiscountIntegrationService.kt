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
 * Writes extras.sc for Cloud → backend SellHandler.
 * Discount from live resolve is not forced here — FREE_CUP is reserved on backend;
 * cash discount from old QR payload is not trusted (state is server-side).
 * op = reservation id from POST /api/loyalty/resolve when present.
 */
class DiscountIntegrationService : IntegrationService() {
    override fun createProcessors(): MutableMap<String, ActionProcessor>? {
        val map = HashMap<String, ActionProcessor>()
        map[ReceiptDiscountEvent.NAME_SELL_RECEIPT] = object : ReceiptDiscountEventProcessor() {
            override fun call(action: String, event: ReceiptDiscountEvent, callback: Callback) {
                try {
                    val ctx = applicationContext
                    val cardValue = CardSession.get(ctx)
                    if (cardValue == null) {
                        callback.skip()
                        return
                    }
                    val isShortCode = cardValue.matches(Regex("\\d{1,18}"))
                    val reservationId = CardSession.reservationId(ctx)
                    val freeFlag = if (CardSession.freeAvailable(ctx)) 1 else 0
                    val sc = JSONObject()
                        .put("v", 2)
                        .put("c", cardValue)
                        .put("kind", if (isShortCode) "code" else "token")
                        .put("op", reservationId ?: "")
                        .put("free", freeFlag)
                        .put("cb", 0)
                        .put("disc", "0")
                        .put("ts", System.currentTimeMillis() / 1000)
                    // No terminal-side monetary discount from stale QR — backend is source of truth.
                    val totalDiscount = BigDecimal.ZERO.setScale(2, RoundingMode.DOWN)
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

    @Suppress("unused")
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
}
