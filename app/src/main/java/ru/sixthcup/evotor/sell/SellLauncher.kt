package ru.sixthcup.evotor.sell

import android.app.Activity
import android.widget.Toast
import ru.evotor.framework.core.IntegrationManagerCallback
import ru.evotor.framework.core.IntegrationManagerFuture
import ru.evotor.framework.core.action.command.open_receipt_command.OpenSellReceiptCommand
import ru.evotor.framework.core.action.event.receipt.changes.position.PositionAdd
import ru.evotor.framework.receipt.Measure
import ru.evotor.framework.receipt.Position
import ru.evotor.framework.receipt.TaxNumber
import ru.sixthcup.evotor.data.Cart
import java.math.BigDecimal
import java.util.UUID

/**
 * Opens a SELL receipt on the terminal with cart positions.
 * Fiscalization is performed by Evotor; DiscountIntegrationService attaches loyalty extras.
 */
object SellLauncher {
    fun openSellReceipt(activity: Activity, onDone: (ok: Boolean, message: String) -> Unit) {
        val lines = Cart.all()
        if (lines.isEmpty()) {
            onDone(false, "Корзина пуста")
            return
        }
        try {
            val changes = lines.map { line ->
                val position = Position.Builder.newInstance(
                    UUID.randomUUID().toString(),
                    line.productUuid,
                    line.name,
                    Measure("шт", 0, 0),
                    line.priceRub,
                    line.quantity,
                ).setTaxNumber(TaxNumber.NO_VAT).build()
                PositionAdd(position)
            }
            // OpenReceiptCommand API differs slightly across library versions — adjust on compile if needed.
            OpenSellReceiptCommand(ArrayList(changes), null).process(
                activity,
                IntegrationManagerCallback { future ->
                    try {
                        val result = future.result
                        when (result?.type) {
                            IntegrationManagerFuture.Result.Type.OK -> {
                                Cart.clear()
                                onDone(true, "Чек открыт в Эвотор — завершите оплату на кассе")
                            }
                            else -> onDone(false, result?.error?.message ?: "Не удалось открыть чек")
                        }
                    } catch (e: Exception) {
                        onDone(false, e.message ?: "Ошибка Integration")
                    }
                },
            )
        } catch (e: Throwable) {
            // Fallback: guide barista to use positions already in cart state + standard flow
            Toast.makeText(
                activity,
                "SDK: ${e.message}. Позиции в корзине; откройте продажу Эвотор вручную — скидка по карте применится.",
                Toast.LENGTH_LONG,
            ).show()
            onDone(false, e.message ?: "OpenReceipt недоступен")
        }
    }
}
