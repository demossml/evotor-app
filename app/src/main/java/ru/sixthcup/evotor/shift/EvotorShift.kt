package ru.sixthcup.evotor.shift

import android.content.Context
import android.os.Handler
import android.os.Looper
import ru.evotor.framework.core.IntegrationManagerCallback
import ru.evotor.framework.core.IntegrationManagerFuture
import ru.evotor.framework.core.action.command.print_cash_command.PrintCashIncomeDocumentCommand
import ru.evotor.framework.core.action.command.print_cash_command.PrintCashOutcomeDocumentCommand
import ru.evotor.framework.core.action.command.print_session_opening_report_command.PrintSessionOpeningReportCommand
import ru.evotor.framework.core.action.command.print_z_report_command.PrintZReportCommand
import ru.evotor.framework.system.SystemStateApi
import java.math.BigDecimal

/**
 * Смена кассы — только то, что Эвотор отдаёт сторонним приложениям.
 * Состояние: SystemStateApi (null = неизвестно, не выдумываем).
 * Операции печатает сам Эвотор; результат — честный: ok / текст ошибки.
 */
object EvotorShift {
    enum class State { OPEN, CLOSED, UNKNOWN }

    data class Info(val state: State, val number: Long?)

    private val main = Handler(Looper.getMainLooper())

    /** Читает состояние в фоне, ответ — в UI-потоке. */
    fun load(ctx: Context, done: (Info) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val info = try {
                val open = SystemStateApi.isSessionOpened(app)
                val num = SystemStateApi.getLastSessionNumber(app)
                Info(when (open) { true -> State.OPEN; false -> State.CLOSED; null -> State.UNKNOWN }, num)
            } catch (t: Throwable) {
                Info(State.UNKNOWN, null)
            }
            main.post { done(Info(info.state, info.number)) }
        }.start()
    }

    fun openShift(ctx: Context, done: (String?) -> Unit) =
        run(done) { cb -> PrintSessionOpeningReportCommand().process(ctx, cb) }

    fun closeShiftZ(ctx: Context, done: (String?) -> Unit) =
        run(done) { cb -> PrintZReportCommand().process(ctx, cb) }

    fun cashIn(ctx: Context, sum: BigDecimal, comment: String, done: (String?) -> Unit) =
        run(done) { cb -> PrintCashIncomeDocumentCommand("6.7 Coffee", comment.ifBlank { "Внесение" }, sum).process(ctx, cb) }

    fun cashOut(ctx: Context, sum: BigDecimal, comment: String, done: (String?) -> Unit) =
        run(done) { cb -> PrintCashOutcomeDocumentCommand("6.7 Coffee", comment.ifBlank { "Изъятие" }, sum).process(ctx, cb) }

    /** done(null) = успех, иначе человекочитаемая ошибка. */
    private fun run(done: (String?) -> Unit, call: (IntegrationManagerCallback) -> Unit) {
        try {
            call(IntegrationManagerCallback { f ->
                val msg = try {
                    val r = f.result
                    if (r.type == IntegrationManagerFuture.Result.Type.OK) null
                    else r.error?.message ?: "Эвотор отклонил операцию"
                } catch (t: Throwable) {
                    t.message ?: "Операция не выполнена"
                }
                main.post { done(msg) }
            })
        } catch (t: Throwable) {
            main.post { done(t.message ?: "Операция недоступна на этом терминале") }
        }
    }
}
