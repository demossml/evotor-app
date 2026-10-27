package ru.sixthcup.evotor.integration

import android.util.Log
import org.json.JSONObject
import ru.evotor.framework.core.IntegrationService
import ru.evotor.framework.core.action.event.receipt.changes.receipt.print_extra.SetPrintExtra
import ru.evotor.framework.core.action.event.receipt.print_extra.PrintExtraRequiredEvent
import ru.evotor.framework.core.action.event.receipt.print_extra.PrintExtraRequiredEventProcessor
import ru.evotor.framework.core.action.event.receipt.print_extra.PrintExtraRequiredEventResult
import ru.evotor.framework.core.action.processor.ActionProcessor
import ru.evotor.devices.commons.printer.printable.IPrintable
import ru.evotor.devices.commons.printer.printable.PrintableText
import ru.evotor.framework.receipt.PrintGroup
import ru.evotor.framework.receipt.Receipt
import ru.evotor.framework.receipt.ReceiptApi
import ru.evotor.framework.receipt.print_extras.PrintExtraPlacePrintGroupTop
import ru.sixthcup.evotor.data.QueueNumber
import ru.sixthcup.evotor.data.QueueNumberStore
import ru.sixthcup.evotor.data.ReceiptPrintText
import ru.sixthcup.evotor.data.SalePrintStore

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
                // Ошибка внутри PrintExtra не должна влиять на печать фискального чека:
                // в любом случае отдаём валидный результат (пустой — если печатать нечего).
                val result = try {
                    buildResult()
                } catch (t: Throwable) {
                    Log.e(TAG, "print_extra_failed", t)
                    PrintExtraRequiredEventResult(emptyList())
                }
                try {
                    callback.onResult(result)
                } catch (t: Throwable) {
                    Log.e(TAG, "print_extra_callback_failed", t)
                }
            }
        },
    )

    private fun buildResult(): PrintExtraRequiredEventResult {
        val number = QueueNumberStore.peek(this)
        val loyalty = SalePrintStore.peek(this)
        val lines = ReceiptPrintText.lines(number, loyalty)
        if (lines.isEmpty()) {
            Log.i(TAG, "print_extra_skip nothing_to_print")
            return PrintExtraRequiredEventResult(emptyList())
        }
        Log.i(
            TAG,
            "print_extra order=${number?.orderId?.take(8) ?: "-"} number=${number?.publicNumber ?: "-"} " +
                "loyaltyCard=${loyalty?.cardFound ?: false} reward=${loyalty?.rewardApplied ?: false} " +
                "lines=${lines.size}",
        )
        // Данные одноразовые: печатаем ровно один раз и освобождаем под следующую продажу.
        QueueNumberStore.clear(this)
        SalePrintStore.clear(this)
        val place = PrintExtraPlacePrintGroupTop(printGroupId())
        val printables = lines.map { PrintableText(it) as IPrintable }.toTypedArray()
        return PrintExtraRequiredEventResult(listOf(SetPrintExtra(place, printables)))
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
