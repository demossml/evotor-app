package ru.sixthcup.evotor.sell

import android.app.Activity
import android.util.Log
import ru.evotor.framework.core.IntegrationManagerCallback
import ru.evotor.framework.core.IntegrationManagerFuture
import ru.evotor.framework.core.action.command.open_receipt_command.OpenSellReceiptCommand
import ru.evotor.framework.core.action.event.receipt.changes.position.PositionAdd
import ru.evotor.framework.core.action.event.receipt.changes.receipt.SetExtra
import ru.evotor.framework.inventory.InventoryApi
import ru.evotor.framework.inventory.ProductItem
import ru.evotor.framework.navigation.NavigationApi
import ru.evotor.framework.receipt.Measure
import ru.evotor.framework.receipt.Position
import ru.sixthcup.evotor.data.CardSession
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CheckoutChoice
import ru.sixthcup.evotor.data.DiscountCalc
import java.util.UUID

/**
 * Передача собранных позиций в Эвотор. APK — не касса: суммы, налоги и оплату считает POS.
 *
 * Docs: https://developer.evotor.ru/docs/doc_java_receipt_creation.html
 * `OpenSellReceiptCommand(positionAddList, setExtra).process(...)` открывает чек продажи;
 * по OK управление сразу возвращается Эвотору — `NavigationApi.createIntentForSellReceiptPayment()`.
 * Товар и все платные добавки уходят одним вызовом списком `PositionAdd` — это штатный
 * механизм integration-library v0.6.27, отдельный «добавить позицию» не нужен.
 */
object SellLauncher {

    data class Outcome(
        val ok: Boolean,
        val message: String,
        /** Позиция не найдена в номенклатуре терминала — нужно обновить каталог. */
        val catalogRefresh: Boolean = false,
    )

    fun openSellReceipt(
        activity: Activity,
        freeApplied: Boolean,
        spendCashback: Boolean,
        cashbackKopecks: Long,
        voucherId: Int = 0,
        onDone: (Outcome) -> Unit,
    ) {
        val lines = Cart.all()
        if (lines.isEmpty()) {
            onDone(Outcome(false, "Корзина пуста"))
            return
        }
        try {
            val positionAdds = ArrayList<PositionAdd>(lines.size)
            var skippedSynthetic = 0
            var missing = 0
            for (line in lines) {
                // Синтетические uuid добавок (mod:…) и пустой uuid не существуют в номенклатуре Эвотор —
                // не кладём «ручную» позицию без НДС/типа (фискальный риск).
                if (!Cart.isSellable(line.productUuid) || line.productUuid.isBlank()) {
                    skippedSynthetic++
                    continue
                }
                // Берём настоящий товар Эвотор (НДС, тип, код, единица измерения — как в нативном меню).
                val item = try { InventoryApi.getProductByUuid(activity, line.productUuid) as? ProductItem.Product } catch (_: Throwable) { null }
                if (item == null) {
                    missing++
                    Log.w("SellLauncher", "position_missing_in_terminal product=${line.productUuid}")
                }
                // Цена в чеке — цена терминала. Расхождение с каталогом только логируем (п.4 контракта).
                val terminalPrice = item?.price
                if (terminalPrice != null && terminalPrice.compareTo(line.priceRub) != 0) {
                    Log.w(
                        "SellLauncher",
                        "price_mismatch product=${line.productUuid} catalog=${line.priceRub.toPlainString()} terminal=${terminalPrice.toPlainString()}",
                    )
                }
                val price = terminalPrice ?: line.priceRub
                val builder = if (item != null) {
                    Position.Builder.newInstance(item, line.quantity).setPrice(price)
                } else {
                    Position.Builder.newInstance(UUID.randomUUID().toString(), line.productUuid, line.name, Measure("шт", 0, 0), price, line.quantity)
                }
                positionAdds.add(PositionAdd(builder.build()))
            }
            if (positionAdds.isEmpty()) {
                onDone(
                    Outcome(
                        false,
                        if (skippedSynthetic > 0) "Нет позиций с товаром Эвотор (добавки без uuid)" else "Корзина пуста",
                    ),
                )
                return
            }
            val card = CardSession.get(activity)
            CheckoutChoice.save(activity, freeApplied, spendCashback, cashbackKopecks, voucherId)
            // Единый контракт extras: { appId: { sc: {v:2,c,op,free,cb,ts,kind} } } + плоский sc.
            var extrasFree = 0
            var extrasCb = 0L
            var extrasHasOp = false
            var extrasVoucher = 0
            val setExtra = if (card != null && card.code.isNotEmpty()) {
                val calc = DiscountCalc.compute(
                    Cart.gross(), Cart.all().filter { it.freeEligible }.map { it.priceRub },
                    DiscountCalc.reserved(card, System.currentTimeMillis() / 1000), freeApplied, card.freeAvailable,
                    if (spendCashback) cashbackKopecks else 0L, card.cashbackKopecks,
                )
                extrasFree = if (calc.free.signum() > 0) 1 else 0
                extrasCb = DiscountCalc.kopecks(calc.cashback)
                extrasHasOp = card.reservationId != null
                // Ваучер пишется только при резерве и совместимости с подарком.
                val voucher = card.vouchers.firstOrNull { it.id == voucherId }
                val voucherOk = calc.free.signum() <= 0 || voucher?.stackableWithFreeCup != false
                extrasVoucher = if (card.reservationId != null && voucher != null && voucherOk) voucher.id else 0
                SetExtra(CardSession.extras(card, extrasFree > 0, extrasCb, voucherId = extrasVoucher.takeIf { it > 0 }))
            } else null

            val positionsTotal = Cart.all().sumOf { line -> line.lineTotal() }
            val hasToppings = lines.any { it.optionId != null }

            OpenSellReceiptCommand(positionAdds, setExtra).process(
                activity,
                IntegrationManagerCallback { future ->
                    try {
                        val result = future.result
                        if (result?.type == IntegrationManagerFuture.Result.Type.OK) {
                            try {
                                activity.startActivity(NavigationApi.createIntentForSellReceiptPayment())
                            } catch (e: Throwable) {
                                Log.e("SellLauncher", "receipt_open_failed ${e.javaClass.simpleName}")
                                onDone(Outcome(false, e.message ?: "Не удалось открыть оплату", missing > 0))
                                return@IntegrationManagerCallback
                            }
                            Log.i(
                                "SellLauncher",
                                "positions_sent count=${positionAdds.size} sum=${positionsTotal.toPlainString()} " +
                                    "toppings=$hasToppings skipped=${skippedSynthetic + missing}",
                            )
                            if (setExtra != null) {
                                Log.i(
                                    "SellLauncher",
                                    "extras_written v=2 hasOp=$extrasHasOp free=$extrasFree cb=$extrasCb voucher=$extrasVoucher",
                                )
                            }
                            onDone(Outcome(true, "Чек открыт", missing > 0))
                        } else {
                            CheckoutChoice.clear(activity)
                            val msg = result?.error?.message ?: "Не удалось открыть чек"
                            Log.e("SellLauncher", "receipt_rejected $msg")
                            onDone(Outcome(false, msg, missing > 0))
                        }
                    } catch (e: Exception) {
                        CheckoutChoice.clear(activity)
                        Log.e("SellLauncher", "integration", e)
                        Log.e("SellLauncher", "receipt_error ${e.javaClass.simpleName}")
                        onDone(Outcome(false, e.message ?: "Integration error", missing > 0))
                    }
                },
            )
        } catch (e: Throwable) {
            CheckoutChoice.clear(activity)
            Log.e("SellLauncher", "openSellReceipt", e)
            Log.e("SellLauncher", "receipt_build_failed ${e.javaClass.simpleName}")
            onDone(Outcome(false, e.message ?: "OpenSellReceiptCommand failed"))
        }
    }
}
