package ru.sixthcup.evotor.integration
import android.os.RemoteException
import org.json.JSONObject
import ru.evotor.framework.core.IntegrationService
import ru.evotor.framework.core.action.event.receipt.discount.ReceiptDiscountEvent
import ru.evotor.framework.core.action.event.receipt.discount.ReceiptDiscountEventProcessor
import ru.evotor.framework.core.action.event.receipt.discount.ReceiptDiscountEventResult
import ru.evotor.framework.core.action.processor.ActionProcessor
import ru.evotor.framework.core.action.event.receipt.changes.receipt.SetExtra
import ru.sixthcup.evotor.data.CardSession
import java.math.BigDecimal
import java.util.UUID
class DiscountIntegrationService : IntegrationService() {
    override fun createProcessors(): MutableMap<String, ActionProcessor>? {
        val map = HashMap<String, ActionProcessor>()
        map[ReceiptDiscountEvent.NAME_SELL_RECEIPT] = object : ReceiptDiscountEventProcessor() {
            override fun call(action: String, event: ReceiptDiscountEvent, callback: Callback) {
                try {
                    val code = CardSession.get(applicationContext) ?: "NONE"
                    val sc = JSONObject()
                        .put("v", 1)
                        .put("c", code)
                        .put("op", UUID.randomUUID().toString())
                        .put("ts", System.currentTimeMillis() / 1000)
                    callback.onResult(
                        ReceiptDiscountEventResult(
                            BigDecimal.ZERO,
                            SetExtra(JSONObject().put("sc", sc)),
                            emptyList(),
                            null,
                        )
                    )
                    CardSession.clear(applicationContext)
                } catch (e: RemoteException) {
                    try { callback.skip() } catch (_: Exception) {}
                } catch (t: Throwable) {
                    try { callback.skip() } catch (_: Exception) {}
                }
            }
        }
        return map
    }
}
