package ru.sixthcup.evotor.integration

import android.util.Log
import org.json.JSONObject
import ru.evotor.framework.core.IntegrationService
import ru.evotor.framework.core.action.event.receipt.changes.receipt.print_extra.SetPrintExtra
import ru.evotor.framework.core.action.event.receipt.print_extra.PrintExtraRequiredEvent
import ru.evotor.framework.core.action.event.receipt.print_extra.PrintExtraRequiredEventProcessor
import ru.evotor.framework.core.action.event.receipt.print_extra.PrintExtraRequiredEventResult
import ru.evotor.framework.core.action.processor.ActionProcessor
import ru.evotor.devices.commons.printer.printable.PrintableText
import ru.evotor.framework.receipt.PrintGroup
import ru.evotor.framework.receipt.Receipt
import ru.evotor.framework.receipt.ReceiptApi
import ru.evotor.framework.receipt.print_extras.PrintExtraPlacePrintGroupTop
import ru.sixthcup.evotor.data.QueueNumber
import ru.sixthcup.evotor.data.QueueNumberStore

/**
 * Печать публичного номера заказа на чеке продажи: «ЗАКАЗ № 47».
 *
 * Официальный механизм Эвотора: `evo.v2.receipt.sell.printExtra.REQUIRED` +
 * `ru.evotor.permission.receipt.printExtra.SET`. Номер приходит с backend
 * (`QueueNumberApi`) и лежит в [QueueNumberStore] под текущую продажу.
 *
 * Ошибка печати/отсутствие номера **не отменяют фискализацию**: сервис молча ничего
 * не добавляет, ошибка уходит в лог.
 */
class ReceiptPrintExtraService : IntegrationService() {

    private companion object {
        const val TAG = "QueuePrintExtra"
    }

    override fun createProcessors(): Map<String, ActionProcessor> = mapOf(
        PrintExtraRequiredEvent.NAME_SELL_RECEIPT to object : PrintExtraRequiredEventProcessor() {
            override fun call(
                action: String,
                event: PrintExtraRequiredEvent,
                callback: ActionProcessor.Callback,
            ) {
                val result = try {
                    buildResult()
                } catch (t: Throwable) {
                    Log.e(TAG, "print_extra_failed", t)
                    null
                }
                callback.onResult(result)
            }
        },
    )

    private fun buildResult(): PrintExtraRequiredEventResult? {
        val number = QueueNumberStore.peek(this) ?: run {
            Log.i(TAG, "print_extra_skip no_number")
            return null
        }
        val place = PrintExtraPlacePrintGroupTop(printGroupId())
        val text = QueueNumber.printTextFor(number.publicNumber)
        Log.i(TAG, "print_extra order=${number.orderId.take(8)} number=${number.publicNumber}")
        return PrintExtraRequiredEventResult(listOf(SetPrintExtra(place, arrayOf(PrintableText(text)))))
    }

    /**
     * Группа печати берётся у текущего чека: у каждой кассы свой идентификатор.
     * Если чек недоступен — используем группу по умолчанию; при ошибке POS extra не напечатает,
     * но чек останется корректным.
     */
    private fun printGroupId(): String {
        val fromReceipt = try {
            ReceiptApi.getReceipt(this, Receipt.Type.SELL)
                ?.printDocuments?.firstOrNull()?.printGroup?.identifier
        } catch (t: Throwable) {
            Log.w(TAG, "print_group_receipt_unavailable: ${t.javaClass.simpleName}")
            null
        }
        return fromReceipt?.takeIf { it.isNotBlank() } ?: PrintGroup.DEFAULT.identifier
    }
}
