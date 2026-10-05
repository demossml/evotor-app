package ru.sixthcup.evotor.integration

import android.os.RemoteException
import org.json.JSONObject
import ru.evotor.framework.core.IntegrationService
import ru.evotor.framework.core.action.event.receipt.discount.ReceiptDiscountEvent
import ru.evotor.framework.core.action.event.receipt.discount.ReceiptDiscountEventProcessor
import ru.evotor.framework.core.action.event.receipt.discount.ReceiptDiscountEventResult
import ru.evotor.framework.core.action.processor.ActionProcessor
import ru.evotor.framework.core.action.event.receipt.changes.receipt.SetExtra
import java.math.BigDecimal
import java.util.UUID

/**
 * Phase 0 Cloud: пишет тестовый extras.sc, скидка 0.
 * Для FACTS 0.6 — видны ли extras в GET documents.
 * API SetExtra/ReceiptDiscountEventResult сверить с integration-library 0.6.x при сборке.
 */
class DiscountIntegrationService : IntegrationService() {
    override fun createProcessors(): MutableMap<String, ActionProcessor>? {
        val map = HashMap<String, ActionProcessor>()
        val processor = object : ReceiptDiscountEventProcessor() {
            override fun call(action: String, event: ReceiptDiscountEvent, callback: Callback) {
                try {
                    val sc = JSONObject()
                        .put("v", 1)
                        .put("kid", "phase0")
                        .put("c", "PHASE0TEST")
                        .put("q", 1)
                        .put("op", UUID.randomUUID().toString())
                        .put("free", 0)
                        .put("cb", 0)
                        .put("disc", "0.00")
                        .put("ts", System.currentTimeMillis() / 1000)
                    val extraRoot = JSONObject().put("sc", sc)
                    // Конструктор Result — при ошибке компиляции сверить Test/javadoc library 0.6.27
                    val result = ReceiptDiscountEventResult(
                        BigDecimal.ZERO,
                        SetExtra(extraRoot),
                        emptyList(),
                        null
                    )
                    callback.onResult(result)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                    try { callback.skip() } catch (_: Exception) {}
                } catch (t: Throwable) {
                    t.printStackTrace()
                    try { callback.skip() } catch (_: Exception) {}
                }
            }
        }
        map[ReceiptDiscountEvent.NAME_SELL_RECEIPT] = processor
        return map
    }
}
