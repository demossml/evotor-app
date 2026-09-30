package ru.sixthcup.evotor.domain

import android.app.Activity
import android.util.Log
import ru.evotor.framework.core.IntegrationException
import ru.evotor.framework.core.IntegrationManagerCallback
import ru.evotor.framework.core.IntegrationManagerFuture
import ru.evotor.framework.core.action.command.open_receipt_command.OpenSellReceiptCommand
import ru.evotor.framework.core.action.event.receipt.changes.position.PositionAdd
import ru.evotor.framework.navigation.NavigationApi
import ru.evotor.framework.receipt.Measure
import ru.evotor.framework.receipt.Position
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CartTotals
import ru.sixthcup.evotor.data.ClientCard
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Real fiscal path on Evotor terminal.
 * Docs:
 * https://developer.evotor.ru/docs/doc_java_receipt_creation.html
 * https://developer.evotor.ru/docs/doc_java_in_app_receipt_payment.html
 * https://developer.evotor.ru/docs/doc_java_discounts.html
 */
class EvotorPaymentGateway : PaymentGateway {

    override fun charge(
        activity: Activity,
        cart: Cart,
        totals: CartTotals,
        card: ClientCard?,
        applyFree: Boolean,
        callback: (PaymentResult) -> Unit
    ) {
        if (cart.isEmpty()) {
            callback(PaymentResult.Err("Корзина пуста"))
            return
        }

        val positions = try {
            buildPositions(cart, totals, applyFree)
        } catch (e: Exception) {
            callback(PaymentResult.Err("Ошибка позиций: ${e.message}"))
            return
        }

        if (positions.isEmpty()) {
            callback(PaymentResult.Err("Нет позиций для чека"))
            return
        }

        try {
            OpenSellReceiptCommand(positions, null).process(
                activity,
                IntegrationManagerCallback { future ->
                    try {
                        val result = future.result
                        when (result?.type) {
                            IntegrationManagerFuture.Result.Type.OK -> {
                                try {
                                    activity.startActivity(
                                        NavigationApi.createIntentForSellReceiptPayment()
                                    )
                                } catch (e: Exception) {
                                    try {
                                        activity.startActivity(
                                            NavigationApi.createIntentForSellReceiptEdit()
                                        )
                                    } catch (e2: Exception) {
                                        Log.e(TAG, "nav", e2)
                                    }
                                }
                                callback(
                                    PaymentResult.OpenedForPayment(
                                        "Чек открыт в Эвоторе. Выберите оплату — фискальный чек напечатается после оплаты."
                                    )
                                )
                            }
                            IntegrationManagerFuture.Result.Type.ERROR -> {
                                val code = result.error?.code
                                val msg = result.error?.message ?: "неизвестно"
                                Log.e(TAG, "OpenSell error $code $msg")
                                try {
                                    activity.startActivity(
                                        NavigationApi.createIntentForSellReceiptEdit()
                                    )
                                } catch (_: Exception) {
                                }
                                callback(
                                    PaymentResult.Err(
                                        "Эвотор: $msg. Если чек уже открыт — закройте или оплатите его, затем повторите."
                                    )
                                )
                            }
                            else -> callback(PaymentResult.Err("Неизвестный ответ Эвотора"))
                        }
                    } catch (e: IOException) {
                        callback(PaymentResult.Err("Связь с Эвотор POS: ${e.message}"))
                    } catch (e: IntegrationException) {
                        callback(PaymentResult.Err("Интеграция: ${e.message}"))
                    } catch (e: Exception) {
                        callback(PaymentResult.Err(e.message ?: "Ошибка открытия чека"))
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "process failed", e)
            callback(
                PaymentResult.Err(
                    "Не удалось вызвать Эвотор SDK. Приложение должно стоять на смарт-терминале. ${e.message}"
                )
            )
        }
    }

    private data class PaidLine(val name: String, val qty: Int, val unitRub: Int)

    private fun buildPositions(
        cart: Cart,
        totals: CartTotals,
        applyFree: Boolean
    ): List<PositionAdd> {
        val result = ArrayList<PositionAdd>()
        val paid = mutableListOf<PaidLine>()
        var grossPaidKopecks = 0

        for (line in cart.snapshot()) {
            val freeUnits = if (line.isFree && applyFree) 1 else 0
            val paidQty = (line.qty - freeUnits).coerceAtLeast(0)
            if (freeUnits > 0) {
                result += positionAdd(
                    name = "${line.product.name} (6-й стакан)",
                    qty = 1,
                    priceRub = BigDecimal(line.product.priceRub),
                    priceWithDiscountRub = BigDecimal.ZERO
                )
            }
            if (paidQty > 0) {
                paid += PaidLine(line.product.name, paidQty, line.product.priceRub)
                grossPaidKopecks += line.product.priceKopecks * paidQty
            }
        }

        val discountAndCbKopecks = totals.discountKopecks + totals.cashbackUseRub * 100
        var remainingDiscount = discountAndCbKopecks

        paid.forEachIndexed { index, p ->
            val lineGross = p.unitRub * 100 * p.qty
            val share = when {
                grossPaidKopecks <= 0 -> 0
                index == paid.lastIndex -> remainingDiscount
                else -> (discountAndCbKopecks.toLong() * lineGross / grossPaidKopecks).toInt()
                    .coerceAtMost(remainingDiscount)
            }
            remainingDiscount = (remainingDiscount - share).coerceAtLeast(0)
            val finalLine = (lineGross - share).coerceAtLeast(0)
            val unitFinal = BigDecimal(finalLine)
                .divide(BigDecimal(p.qty * 100), 2, RoundingMode.HALF_UP)
            result += positionAdd(
                name = p.name,
                qty = p.qty,
                priceRub = BigDecimal(p.unitRub),
                priceWithDiscountRub = unitFinal
            )
        }
        return result
    }

    private fun positionAdd(
        name: String,
        qty: Int,
        priceRub: BigDecimal,
        priceWithDiscountRub: BigDecimal
    ): PositionAdd {
        val position = Position.Builder.newInstance(
            UUID.randomUUID().toString(),
            name,
            "шт",
            Measure("шт", 0, 0),
            priceRub,
            BigDecimal(qty)
        )
            .setPriceWithDiscountPosition(priceWithDiscountRub)
            .build()
        return PositionAdd(position)
    }

    companion object {
        private const val TAG = "EvotorPay"
    }
}
