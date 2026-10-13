package ru.sixthcup.evotor.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import java.math.BigDecimal
import java.math.RoundingMode

/** Общие цвета и мелкие помощники кассового UI (токены 6.7: бренд #002FA7, светлые нейтральные, оранжевый — только подарок/акция). */
object UiKit {
    val BRAND = 0xFF002FA7.toInt()
    val SOFT = 0xFFEEF4FF.toInt()
    val PAGE = 0xFFF5F7FB.toInt()
    val INK = 0xFF1E293B.toInt()
    val HINT = 0xFF475569.toInt()
    val LINE = 0xFFCBD5E1.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    val OK = 0xFF1E6B4A.toInt()
    /** Цена по акции (красная) — только когда есть `#old=`. */
    val PROMO = 0xFFB3261E.toInt()
    val SKELETON = 0xFFE2E8F0.toInt()

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
