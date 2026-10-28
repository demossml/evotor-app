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
import ru.sixthcup.evotor.data.QueueNumberStore
import ru.sixthcup.evotor.data.SalePrintLoyalty
import ru.sixthcup.evotor.data.SalePrintStore
import ru.sixthcup.evotor.data.CardInfo
import ru.sixthcup.evotor.data.CardProgress
import ru.sixthcup.evotor.data.VoucherCalc
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
                        Log.i("DiscountIntegration", "loyalty_discount_skip reason=no_card")
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
                    val eligibleUnitPrices = Cart.giftEligibleLines().map { it.priceRub }
                    val requestedCb = if (CheckoutChoice.cashback(applicationContext)) CheckoutChoice.cashbackKopecks(applicationContext) else 0L
                    val freeChosen = CheckoutChoice.free(applicationContext)
                    val calc = DiscountCalc.compute(gross, eligibleUnitPrices, reserved, freeChosen, info.freeAvailable, requestedCb, info.cashbackKopecks)
                    val freeDiscount = calc.free
                    val cbRub = calc.cashback
                    val freeApplied = freeDiscount.signum() > 0
                    // Скидка комбо — фиксированная выгода набора (позиции в чеке реальные, цена полная).
                    // Ваучер: применяется только с резервом и, если не stackable, без подарка.
                    val chosenVoucher = info.vouchers.firstOrNull { it.id == CheckoutChoice.voucherId(applicationContext) }
                    val voucherAllowed = reserved && VoucherCalc.allowedTogether(chosenVoucher, freeChosen && freeApplied)
                    val voucherDiscount = if (voucherAllowed) {
                        VoucherCalc.discount(gross.subtract(calc.free), chosenVoucher)
                    } else BigDecimal.ZERO
                    val comboRub = CheckoutChoice.comboRub(applicationContext)
                    val total = calc.total.add(voucherDiscount).add(comboRub).min(gross)
                    val cbKopecks = cbRub.multiply(BigDecimal(100)).setScale(0, RoundingMode.DOWN).toLong()
                    logDiscount(
                        reserved = reserved,
                        freeChosen = freeChosen,
                        freeAvail = info.freeAvailable,
                        eligibleCount = eligibleUnitPrices.size,
                        freeRub = freeDiscount,
                        cbRub = cbRub,
                        hasOp = info.reservationId != null,
                    )
                    // Снимок для печати: PrintExtra сработает после оплаты, спросить сервер будет нельзя.
                    savePrintSnapshot(info, freeApplied, cbKopecks)
                    Log.i(
                        "DiscountIntegration",
                        "extras_written v=2 hasOp=${info.reservationId != null} free=${if (freeApplied) 1 else 0} " +
                            "cb=$cbKopecks voucher=${if (voucherAllowed) chosenVoucher?.id ?: 0 else 0}",
                    )
                    callback.onResult(
                        ReceiptDiscountEventResult(
                            total,
                            SetExtra(
                            CardSession.extras(
                                info,
                                freeApplied,
                                cbKopecks,
                                QueueNumberStore.peek(applicationContext),
                                voucherId = if (voucherAllowed) chosenVoucher?.id else null,
                            ),
                        ),
                            emptyList(),
                            null,
                        ),
                    )
                } catch (_: Throwable) {
                    try { callback.skip() } catch (_: Exception) {}
                }
                // ВАЖНО: CheckoutChoice здесь НЕ чистим. Эвотор может пересчитать чек несколько раз
                // (добавили позицию, сменили оплату) — очистка здесь отнимала уже применённый подарок.
                // Выбор живёт до конца чека: чистится при подтверждении оплаты или отмене.
            }
        }
        return map
    }

    /** (unit price, line total) for every position of the open sell receipt. */
    /**
     * Диагностика: почему скидка применена или не применена. По этим строкам на терминале
     * видно причину без разбора кода.
     */
    private fun logDiscount(
        reserved: Boolean,
        freeChosen: Boolean,
        freeAvail: Int,
        eligibleCount: Int,
        freeRub: BigDecimal,
        cbRub: BigDecimal,
        hasOp: Boolean,
    ) {
        if (freeRub.signum() > 0 || cbRub.signum() > 0) {
            Log.i(
                "DiscountIntegration",
                "loyalty_discount reserved=$reserved freeChosen=$freeChosen freeAvail=$freeAvail " +
                    "eligibleCount=$eligibleCount freeRub=${freeRub.toPlainString()} cbRub=${cbRub.toPlainString()} " +
                    "op=${if (hasOp) "yes" else "no"}",
            )
            return
        }
        val reason = when {
            !reserved -> "no_reservation"
            freeChosen && eligibleCount == 0 -> "no_eligible"
            freeChosen && freeAvail <= 0 -> "no_free_available"
            !freeChosen -> "not_chosen"
            else -> "no_benefit"
        }
        Log.i(
            "DiscountIntegration",
            "loyalty_discount_skip reason=$reason reserved=$reserved freeChosen=$freeChosen " +
                "freeAvail=$freeAvail eligibleCount=$eligibleCount op=${if (hasOp) "yes" else "no"}",
        )
    }

    /** Что напечатать про лояльность на этом чеке. Персональных данных нет. */
    private fun savePrintSnapshot(info: CardInfo?, freeApplied: Boolean, cbKopecks: Long) {
        try {
            val card = info?.takeIf { it.code.isNotEmpty() || it.cardCode.isNotEmpty() }
            if (card == null) {
                SalePrintStore.save(
                    applicationContext,
                    SalePrintLoyalty(cardFound = false, progressCurrent = 0, progressTarget = 0, progressKnown = false),
                )
                return
            }
            val progress = CardProgress.of(card)
            SalePrintStore.save(
                applicationContext,
                SalePrintLoyalty(
                    cardFound = true,
                    progressCurrent = progress.cups,
                    progressTarget = progress.goal,
                    // Для ручного кода сервер скрывает прогресс — недостоверное «0 из 5» не печатаем.
                    progressKnown = progress.giftKnown,
                    // Чашки считает backend в poll; на кассе этой цифры ещё нет — не выдумываем.
                    cupsEarnedThisSale = 0,
                    rewardApplied = freeApplied || cbKopecks > 0,
                    rewardTitle = if (freeApplied) "Бесплатный кофе" else if (cbKopecks > 0) "Кэшбэк" else null,
                ),
            )
        } catch (t: Throwable) {
            Log.w("DiscountIntegration", "print_snapshot_failed", t)
        }
    }

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
