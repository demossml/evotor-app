package ru.sixthcup.evotor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/**
 * Кружок с инициалом — `.guest-avatar` / `.shift-avatar` из index.
 * Одна View с Canvas-отрисовкой: без вложенных TextView и без аллокаций на bind,
 * поэтому безопасно в списках команды и табеля.
 */
class AvatarView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private var letter: String = ""
    private var solid: Boolean = true

    /**
     * @param letter инициал (первая буква имени/кода карты); пустая строка → «?»
     * @param solid true — тёмный круг с белой буквой; false — пустой пунктирный (нет карты)
     * @param textSp размер буквы в sp
     */
    fun bind(letter: String?, solid: Boolean = true, textSp: Float = 11.5f) {
        this.letter = letter?.trim()?.take(1)?.uppercase().orEmpty().ifEmpty { "?" }
        this.solid = solid
        text.textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, textSp, resources.displayMetrics,
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = min(w, h) / 2f
        val cx = w / 2f
        val cy = h / 2f

        if (solid) {
            fill.color = UiKit.INK
            canvas.drawCircle(cx, cy, r, fill)
            text.color = UiKit.WHITE
        } else {
            fill.color = UiKit.SURFACE_2
            canvas.drawCircle(cx, cy, r, fill)
            ring.color = UiKit.LINE_STRONG
            ring.strokeWidth = resources.displayMetrics.density
            canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
            text.color = UiKit.INK_2
        }

        val baseline = cy - (text.descent() + text.ascent()) / 2f
        canvas.drawText(letter, cx, baseline, text)
    }
}
