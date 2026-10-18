package ru.sixthcup.evotor.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Токены UI кассы 6.7 — 100% parity с index-design (assets/base.css).
 * Klein-синий только на primary CTA; тёплая бумага на фоне; белые карточки.
 */
object UiKit {
    // Brand
    val BRAND = 0xFF002FA7.toInt()
    val BRAND_DEEP = 0xFF00227A.toInt()
    val BRAND_SOFT = 0xFFE9EDFB.toInt()

    // Surfaces
    val PAGE = 0xFFF6F3EE.toInt()       // --bg
    val PAGE_DEEP = 0xFFEFE9E0.toInt()  // --bg-deep
    val SURFACE = 0xFFFFFFFF.toInt()    // --surface
    val SURFACE_2 = 0xFFFBF9F5.toInt()  // --surface-2

    // Ink
    val INK = 0xFF17140F.toInt()        // --ink
    val INK_2 = 0xFF464036.toInt()      // --ink-2
    val MUTED = 0xFF776F61.toInt()      // --muted
    val LINE = 0xFFE7DFD3.toInt()       // --line
    val LINE_STRONG = 0xFFD7CCBC.toInt() // --line-strong

    // Semantic
    val SUCCESS = 0xFF1C7A4B.toInt()
    val SUCCESS_SOFT = 0xFFE3F1E8.toInt()
    val WARN = 0xFFA2551F.toInt()
    val WARN_SOFT = 0xFFF9E9DC.toInt()
    val DANGER = 0xFFA52A2A.toInt()
    val DANGER_SOFT = 0xFFF8E4E1.toInt()

    // Legacy aliases (call sites)
    val SOFT = BRAND_SOFT
    val HINT = INK_2
    val WHITE = SURFACE
    val OK = SUCCESS
    val PROMO = 0xFFB3261E.toInt()
    val SKELETON = 0xFFE8E4DE.toInt()
    val BAD = DANGER

    // Shape — base.css: --r-s 10 / --r-m 14 / --r-l 20 / --r-xl 26 / --r-pill
    const val R_S = 10
    const val R_M = 14
    const val R_L = 20
    const val R_XL = 26
    const val R_PILL = 999

    /** Тап-зона пальцем по правилам index: не меньше 48 dp. */
    const val TAP = 48

    fun rub(v: BigDecimal): String {
        val s = v.setScale(2, RoundingMode.HALF_UP)
        return (if (s.remainder(BigDecimal.ONE).signum() == 0) s.toBigInteger().toString() else s.toPlainString()) + " ₽"
    }

    fun dp(view: View, v: Int): Int = (v * view.resources.displayMetrics.density).toInt()

    fun rounded(density: Float, color: Int, radiusDp: Int, stroke: Int?): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
            if (stroke != null) setStroke(maxOf(1, density.toInt()), stroke)
        }

    /**
     * Прогресс чашек `.lc-dots` из index: `goal` сегментов, заполнены `cups`,
     * последний незаполненный рисуется пунктиром как «подарок впереди».
     */
    fun loyaltyDots(
        context: android.content.Context,
        cups: Int,
        goal: Int,
        onColor: Int = PAGE,
        offColor: Int = 0x38000000,
        giftOutline: Int = 0x8CF6F3EE.toInt(),
    ): android.widget.LinearLayout {
        val density = context.resources.displayMetrics.density
        val row = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val filledCount = minOf(maxOf(cups, 0), goal)
        repeat(maxOf(goal, 1)) { i ->
            val on = i < filledCount
            val gift = i == goal - 1 && !on
            val seg = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 99 * density
                    setColor(if (on) onColor else offColor)
                    if (gift) setStroke(maxOf(1, density.toInt()), giftOutline)
                }
            }
            row.addView(
                seg,
                android.widget.LinearLayout.LayoutParams(0, (6 * density).toInt(), 1f).apply {
                    if (i > 0) marginStart = (5 * density).toInt()
                },
            )
        }
        return row
    }
}
