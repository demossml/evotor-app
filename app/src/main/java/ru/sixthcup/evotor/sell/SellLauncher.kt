package ru.sixthcup.evotor.sell

import android.app.Activity
import android.widget.Toast
import org.json.JSONObject
import ru.evotor.framework.core.IntegrationManagerCallback
import ru.evotor.framework.core.IntegrationManagerFuture
import ru.evotor.framework.core.action.command.open_receipt_command.OpenSellReceiptCommand
import ru.evotor.framework.core.action.event.receipt.changes.position.PositionAdd
import ru.evotor.framework.core.action.event.receipt.changes.receipt.SetExtra
import ru.evotor.framework.navigation.NavigationApi
import ru.evotor.framework.receipt.Measure
import ru.evotor.framework.receipt.Position
import ru.sixthcup.evotor.data.CardSession
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CheckoutChoice
import java.util.UUID

/**
 * Docs: https://developer.evotor.ru/docs/doc_java_receipt_creation.html
 * OpenSellReceiptCommand(positionAddList, setExtra).process → NavigationApi.createIntentForSellReceiptPayment()
 */
object SellLauncher {
    fun openSellReceipt(activity: Activity, freeApplied: Boolean, spendCashback: Boolean, cashbackKopecks: Long, onDone: (Boolean, String) -> Unit) {
        val lines = Cart.all()
        if (lines.isEmpty()) {
            onDone(false, "Корзина пуста")
            return
        }
        try {
            val positionAdds = ArrayList<PositionAdd>()
            for (line in lines) {
                val position = Position.Builder.newInstance(
                    UUID.randomUUID().toString(),
                    line.productUuid,
                    line.name,
                    Measure("шт", 0, 0),
                    line.priceRub,
                    line.quantity,
                ).build()
                positionAdds.add(PositionAdd(position))
            }
            val card = CardSession.get(activity)
            CheckoutChoice.save(activity, freeApplied, spendCashback, cashbackKopecks)
            // Single contract consumed by SellHandler: extras.sc={v:2,c,op,free,cb,ts}.
            val setExtra = if (card != null && card.code.isNotEmpty()) {
                SetExtra(JSONObject().put("sc", CardSession.sc(card, freeApplied, if (spendCashback) cashbackKopecks else 0L)))
            } else null

            OpenSellReceiptCommand(positionAdds, setExtra).process(
                activity,
                IntegrationManagerCallback { future ->
                    try {
                        val result = future.result
                        if (result?.type == IntegrationManagerFuture.Result.Type.OK) {
                            try {
                                activity.startActivity(NavigationApi.createIntentForSellReceiptPayment())
                            } catch (e: Throwable) {
                                onDone(false, e.message ?: "Не удалось открыть оплату")
                                return@IntegrationManagerCallback
                            }
                            onDone(true, "Чек открыт. После возврата подтвердите результат фискализации")
                        } else {
                            CheckoutChoice.clear(activity)
                            onDone(false, result?.error?.message ?: "Не удалось открыть чек")
                        }
                    } catch (e: Exception) {
                        CheckoutChoice.clear(activity)
                        onDone(false, e.message ?: "Integration error")
                    }
                },
            )
        } catch (e: Throwable) {
            CheckoutChoice.clear(activity)
            Toast.makeText(activity, "Sell: ${e.message}", Toast.LENGTH_LONG).show()
            onDone(false, e.message ?: "OpenSellReceiptCommand failed")
        }
    }
}
