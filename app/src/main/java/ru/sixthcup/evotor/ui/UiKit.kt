package ru.sixthcup.evotor.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Токены UI кассы 6.7 — в паритете с PWA app.67coffee.ru:
 * brand #002FA7, page #F6F3EE, surface white.
 * Литералы здесь — единый runtime-источник (View-код без Context);
 * XML colors.xml / themes.xml совпадают с этими значениями.
 */
object UiKit {
    val BRAND = 0xFF002FA7.toInt()
    val SOFT = 0xFFEEF4FF.toInt()
    /** Тёплый фон страницы = PWA body #F6F3EE */
    val PAGE = 0xFFF6F3EE.toInt()
    val INK = 0xFF0B1220.toInt()
    val HINT = 0xFF3D4A5C.toInt()
    val LINE = 0xFFD6D0C8.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    val OK = 0xFF2B7D5B.toInt()
    val PROMO = 0xFFB3261E.toInt()
    val SKELETON = 0xFFE8E4DE.toInt()

    fun rub(v: BigDecimal): String {
        val s = v.setScale(2, RoundingMode.HALF_UP)
        return (if (s.remainder(BigDecimal.ONE).signum() == 0) s.toBigInteger().toString() else s.toPlainString()) + " ₽"
    }

    fun dp(view: View, v: Int): Int = (v * view.resources.displayMetrics.density).toInt()

    fun rounded(density: Float, color: Int, radiusDp: Int, stroke: Int?): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * density
        if (stroke != null) setStroke(maxOf(1, density.toInt()), stroke)
    }
}
