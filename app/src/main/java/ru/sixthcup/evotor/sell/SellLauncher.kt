package ru.sixthcup.evotor.sell

import android.app.Activity
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
import ru.sixthcup.evotor.data.DiscountCalc
import ru.evotor.framework.inventory.InventoryApi
import ru.evotor.framework.inventory.ProductItem
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
            var skippedSynthetic = 0
            for (line in lines) {
                // Синтетические uuid добавок (mod:…) не существуют в номенклатуре Эвотор —
                // не кладём «ручную» позицию без НДС/типа (фискальный риск).
                if (!Cart.isSellable(line.productUuid)) {
                    skippedSynthetic++
                    continue
                }
                // Берём настоящий товар Эвотор (НДС, тип, код, единица измерения — как в нативном меню);
                // цену подставляем ту же рублёвую, что видел бариста. Запасной путь — позиция «вручную».
                val item = try { InventoryApi.getProductByUuid(activity, line.productUuid) as? ProductItem.Product } catch (_: Throwable) { null }
                val builder = if (item != null) {
                    Position.Builder.newInstance(item, line.quantity).setPrice(line.priceRub)
                } else {
                    Position.Builder.newInstance(UUID.randomUUID().toString(), line.productUuid, line.name, Measure("шт", 0, 0), line.priceRub, line.quantity)
                }
                positionAdds.add(PositionAdd(builder.build()))
            }
            if (positionAdds.isEmpty()) {
                onDone(false, if (skippedSynthetic > 0) "Нет позиций с товаром Эвотор (добавки без uuid)" else "Корзина пуста")
                return
            }
            val card = CardSession.get(activity)
            CheckoutChoice.save(activity, freeApplied, spendCashback, cashbackKopecks)
            // Единый контракт для SellHandler: extras.sc={v:2,c,op,free,cb,ts}.
            // free/cb считаются тем же DiscountCalc, что и окно «К оплате» и DiscountIntegrationService.
            val setExtra = if (card != null && card.code.isNotEmpty()) {
                val calc = DiscountCalc.compute(
                    Cart.gross(), Cart.all().filter { it.freeEligible }.map { it.priceRub },
                    DiscountCalc.reserved(card, System.currentTimeMillis() / 1000), freeApplied, card.freeAvailable,
                    if (spendCashback) cashbackKopecks else 0L, card.cashbackKopecks,
                )
                SetExtra(JSONObject().put("sc", CardSession.sc(card, calc.free.signum() > 0, DiscountCalc.kopecks(calc.cashback))))
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
                            onDone(true, "Чек открыт")
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
            onDone(false, e.message ?: "OpenSellReceiptCommand failed")
        }
    }
}
