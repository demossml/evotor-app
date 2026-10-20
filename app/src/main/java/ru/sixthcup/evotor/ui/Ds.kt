package ru.sixthcup.evotor.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * Метрики витрины из прототипа (assets/app.css): режимы 5″ / 7″ / 10″.
 * Режим выбирается по ширине экрана в dp, как `fitDevice()` по габаритам макета:
 * 5″ = 390×720, 7″ = 600×900, 10″ = 1060×720 (корзина закреплена справа, 372).
 */
object Ds {
    enum class Mode { M5, M7, M10 }

    class Metrics(
        val mode: Mode,
        val headPadH: Int, val headPadTop: Int, val headPadBottom: Int,
        val sidePad: Int,
        val focusW: Int,
        val cols: Int, val gap: Int,
        val mediaH: Int, val imgW: Int, val imgH: Int,
        val nameSp: Float, val metaSp: Float, val priceSp: Float,
        val barInset: Int, val barBottom: Int, val barH: Int, val ctaH: Int, val sumSp: Float,
        val cartDockW: Int,
    )

    private val M5 = Metrics(Mode.M5, 14, 14, 8, 14, 158, 2, 9, 96, 100, 80, 12.5f, 9f, 14f, 11, 10, 66, 48, 17f, 0)
    private val M7 = Metrics(Mode.M7, 20, 18, 10, 20, 172, 3, 11, 120, 124, 100, 13.5f, 9f, 15f, 16, 14, 70, 50, 19f, 0)
    private val M10 = Metrics(Mode.M10, 22, 16, 10, 22, 178, 3, 12, 126, 132, 106, 14f, 9.5f, 16f, 16, 14, 70, 50, 19f, 372)

    fun mode(ctx: Context): Mode {
        val w = ctx.resources.configuration.screenWidthDp
        return when {
            w >= 900 -> Mode.M10
            w >= 520 -> Mode.M7
            else -> Mode.M5
        }
    }

    fun metrics(ctx: Context): Metrics = when (mode(ctx)) {
        Mode.M5 -> M5
        Mode.M7 -> M7
        Mode.M10 -> M10
    }

    /**
     * Минимальный кегль на кассе — 10 sp: в макете есть подписи 7–9.5 px, но на 5″ терминале
     * (≈1.2 мм) их не прочитать. Всё, что меньше, поднимается до 10.
     */
    fun sp(px: Float): Float = if (px < 10f) 10f else px

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())

    fun rounded(ctx: Context, color: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * ctx.resources.displayMetrics.density
            if (stroke != null) setStroke(maxOf(1, (strokeDp * ctx.resources.displayMetrics.density).toInt()), stroke)
        }

    /** Градиент плитки: `radial-gradient(#f4efe6 → #ece4d8)`; радиальный даёт неровности на слабых GPU, берём вертикальный. */
    fun mediaGradient(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFFF4EFE6.toInt(), 0xFFECE4D8.toInt()),
    )

    /** --shadow-s: лёгкая тень карточек. */
    fun shadowS(v: View) { v.elevation = 1.5f * v.resources.displayMetrics.density }
}

/** Векторная иконка из res/drawable (белый штрих) с нужным цветом. */
fun icon(ctx: Context, res: Int, sizeDp: Float, color: Int): android.widget.ImageView =
    android.widget.ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        layoutParams = android.view.ViewGroup.LayoutParams(Ds.dp(ctx, sizeDp), Ds.dp(ctx, sizeDp))
    }
