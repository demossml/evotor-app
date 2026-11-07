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
import ru.evotor.framework.receipt.Position
import ru.sixthcup.evotor.data.CardSession
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CheckoutChoice
import ru.sixthcup.evotor.data.Choice
import ru.sixthcup.evotor.data.ChoicePrint
import ru.sixthcup.evotor.data.ChoicePrintStore
import ru.sixthcup.evotor.data.DiscountCalc
import ru.sixthcup.evotor.data.FiscalCalc
import ru.sixthcup.evotor.data.FiscalLines
import ru.sixthcup.evotor.data.PreorderPullSession
import ru.sixthcup.evotor.data.VoucherCalc

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
        /** Чек не открывался: позиции в Эвотор не передавались (частичной продажи нет). */
        val blockedBeforeSend: Boolean = false,
    )

    fun openSellReceipt(
        activity: Activity,
        freeApplied: Boolean,
        spendCashback: Boolean,
        cashbackKopecks: Long,
        voucherId: Int? = null,
        /**
         * Номенклатура, уже проверенная вызывающим до резервирования номера очереди.
         * `null` — искать в терминале здесь (старое поведение).
         */
        preflight: Map<String, ProductItem.Product>? = null,
        onDone: (Outcome) -> Unit,
    ) {
        val lines = Cart.all()
        if (lines.isEmpty()) {
            onDone(Outcome(false, "Корзина пуста"))
            return
        }
        try {
            val positionAdds = ArrayList<PositionAdd>(lines.size + 2)
            // Льгота уходит в чек ценами позиций: receiptDiscount срабатывает не на всех прошивках.
            val split = FiscalCalc.split(
                Cart.gross(), Cart.giftEligibleLines().map { it.priceRub },
                CardSession.get(activity), Choice.normalize(freeApplied, spendCashback, cashbackKopecks, voucherId),
                System.currentTimeMillis() / 1000,
            )
            val fiscalLines = FiscalLines.build(lines, split)
            if (split.positionDiscountKopecks > 0) {
                Log.i(
                    "SellLauncher",
                    "fiscal_positions discount=${split.positionDiscountKopecks} gift=${split.giftApplied} " +
                        "voucher=${split.voucherId ?: 0} floor=${split.floorApplied} lines=${fiscalLines.size}",
                )
            }
            // Решение «какие строки корзины станут позициями и по какой цене» считает чистый
            // ReceiptPlan (его же проверяют тесты); здесь только обращение к номенклатуре терминала.
            val terminal = HashMap<String, ProductItem.Product?>()
            val plan = ReceiptPlan.plan(fiscalLines) { uuid ->
                val item = preflight?.get(uuid) ?: try {
                    InventoryApi.getProductByUuid(activity, uuid) as? ProductItem.Product
                } catch (_: Throwable) {
                    null
                }
                terminal[uuid] = item
                item?.let { TerminalItem(it.uuid, it.price) }
            }
            // Товара нет в номенклатуре — чек не открываем: «ручная» позиция из
            // newInstance(uuid, productUuid, name, Measure, …) получает ProductType.NORMAL
            // и TaxNumber = null, корректную фискализацию гарантировать нельзя.
            val blocked = ReceiptPlan.blockReason(plan)
            if (blocked != null) {
                plan.missing.forEach { m ->
                    Log.e("SellLauncher", "position_missing_in_terminal product=${m.productUuid} name=${m.name}")
                }
                onDone(
                    Outcome(
                        ok = false,
                        message = blocked,
                        catalogRefresh = plan.missing.isNotEmpty(),
                        blockedBeforeSend = true,
                    ),
                )
                return
            }
            plan.priceMismatch.forEach { uuid ->
                Log.w("SellLauncher", "price_mismatch product=$uuid")
            }
            plan.positions.forEach { pos ->
                // Позиция с настоящим товаром Эвотор несёт его НДС/тип/код/единицу измерения.
                val item = terminal[pos.productUuid]
                    ?: error("position without terminal item ${pos.productUuid}")
                val builder = Position.Builder.newInstance(item, pos.quantity).setPrice(pos.price)
                // Цена со скидкой — только когда она реально ниже; 0 ₽ не бывает (пол 1 ₽ в FiscalCalc).
                pos.discountPrice?.let { discounted ->
                    builder.setPriceWithDiscountPosition(discounted)
                    Log.i(
                        "SellLauncher",
                        "fiscal_pos product=${pos.productUuid} price=${pos.price.toPlainString()} " +
                            "withDiscount=${discounted.toPlainString()} qty=${pos.quantity.toPlainString()}",
                    )
                }
                positionAdds.add(PositionAdd(builder.build()))
            }
            if (positionAdds.isEmpty()) {
                // Недостижимо после blockReason, но чек без позиций открывать нельзя.
                onDone(Outcome(false, "Корзина пуста"))
                return
            }
            val card = CardSession.get(activity)
            // Решение кассира фиксируем до открытия чека: модель сама снимает «ваучер + кэшбэк».
            CheckoutChoice.save(activity, freeApplied, spendCashback, cashbackKopecks, voucherId)
            // Единый контракт extras: { appId: { sc: {v:2,c,op,free,cb,ts,kind} } } + плоский sc.
            var extrasFree = 0
            var extrasCb = 0L
            var extrasHasOp = false
            var extrasVoucher = 0
            val setExtra = if (card != null && card.code.isNotEmpty()) {
                val reserved = DiscountCalc.reserved(card, System.currentTimeMillis() / 1000)
                val eligible = Cart.giftEligibleLines().map { it.priceRub }
                val voucher = card.vouchers.firstOrNull { it.id == voucherId }
                val calc = DiscountCalc.compute(
                    Cart.gross(), eligible, reserved, freeApplied, card.freeAvailable,
                    if (spendCashback) cashbackKopecks else 0L, card.cashbackKopecks,
                    voucher = voucher,
                    voucherAllowed = VoucherCalc.allowedTogether(
                        voucher,
                        DiscountCalc.freeApplies(reserved, freeApplied, card.freeAvailable, eligible),
                    ),
                )
                extrasFree = if (calc.free.signum() > 0) 1 else 0
                extrasCb = DiscountCalc.kopecks(calc.cashback)
                extrasHasOp = card.reservationId != null
                // Ваучер уходит только если он реально даёт скидку (резерв есть, stackable соблюдён).
                extrasVoucher = if (calc.voucher.signum() > 0) voucher?.id ?: 0 else 0
                Log.i(
                    "SellLauncher",
                    "receipt_discount free=${extrasFree} cb=$extrasCb voucher=$extrasVoucher " +
                        "voucherRub=${calc.voucher.toPlainString()} total=${calc.total.toPlainString()}",
                )
                SetExtra(
                    CardSession.extras(
                        card, extrasFree > 0, extrasCb,
                        voucherId = extrasVoucher.takeIf { it > 0 },
                        // Предзаказ, состав которого лежит в этом чеке: backend свяжет SELL с заказом.
                        preorderId = PreorderPullSession.orderId(),
                    ),
                )
            } else null

            val positionsTotal = Cart.all().sumOf { line -> line.lineTotal() }
            val hasToppings = lines.any { it.optionId != null }

            // Выбор без цены (корица): печатается PrintExtra, в позиции чека не входит. Сохраняем
            // до открытия чека — PrintExtra срабатывает уже после оплаты.
            ChoicePrintStore.save(activity, ChoicePrint.entriesFrom(lines))

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
                                onDone(Outcome(false, e.message ?: "Не удалось открыть оплату"))
                                return@IntegrationManagerCallback
                            }
                            Log.i(
                                "SellLauncher",
                                "positions_sent count=${positionAdds.size} sum=${positionsTotal.toPlainString()} " +
                                    "toppings=$hasToppings",
                            )
                            if (setExtra != null) {
                                Log.i(
                                    "SellLauncher",
                                    "extras_written v=2 hasOp=$extrasHasOp free=$extrasFree cb=$extrasCb voucher=$extrasVoucher",
                                )
                            }
                            onDone(Outcome(true, "Чек открыт"))
                        } else {
                            CheckoutChoice.clear(activity)
                            ChoicePrintStore.clear(activity)
                            val msg = result?.error?.message ?: "Не удалось открыть чек"
                            Log.e("SellLauncher", "receipt_rejected $msg")
                            onDone(Outcome(false, msg))
                        }
                    } catch (e: Exception) {
                        CheckoutChoice.clear(activity)
                        ChoicePrintStore.clear(activity)
                        Log.e("SellLauncher", "integration", e)
                        Log.e("SellLauncher", "receipt_error ${e.javaClass.simpleName}")
                        onDone(Outcome(false, e.message ?: "Integration error"))
                    }
                },
            )
        } catch (e: Throwable) {
            CheckoutChoice.clear(activity)
            ChoicePrintStore.clear(activity)
            Log.e("SellLauncher", "openSellReceipt", e)
            Log.e("SellLauncher", "receipt_build_failed ${e.javaClass.simpleName}")
            onDone(Outcome(false, e.message ?: "OpenSellReceiptCommand failed"))
        }
    }
}
