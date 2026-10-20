package ru.sixthcup.evotor.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import ru.sixthcup.evotor.R

/**
 * Шрифт Onest из прототипа (assets/fonts → res/font). Веса те же, что в app.css:
 * 400 / 500 / 600 / 650 / 700.
 *
 * Код UI собирается программно и местами вызывает `setTypeface(null, BOLD)`, что сбрасывает
 * кастомный шрифт. Поэтому после сборки любого экрана/листа вызывается [apply]: обход дерева
 * и подстановка Onest по уже выставленному стилю (жирный → 650, обычный → 400).
 * Где нужен конкретный вес — [weight].
 */
object Fonts {
    const val REGULAR = 400
    const val MEDIUM = 500
    const val SEMIBOLD = 600
    const val STRONG = 650
    const val BOLD = 700

    private val cache = HashMap<Int, Typeface>()

    fun face(ctx: Context, w: Int): Typeface = synchronized(cache) {
        cache.getOrPut(w) {
            val res = when (w) {
                REGULAR -> R.font.onest_regular
                MEDIUM -> R.font.onest_medium
                SEMIBOLD -> R.font.onest_semibold
                STRONG -> R.font.onest_strong
                else -> R.font.onest_bold
            }
            try { ResourcesCompat.getFont(ctx.applicationContext, res) } catch (_: Throwable) { null }
                ?: Typeface.create(Typeface.DEFAULT, if (w >= STRONG) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** Явный вес: помечаем view, чтобы [apply] его не перезаписал. */
    fun weight(tv: TextView, w: Int) {
        tv.typeface = face(tv.context, w)
        tv.setTag(R.id.tag_font_weight, w)
    }

    fun apply(v: View?) {
        if (v == null) return
        if (v is TextView) {
            val fixed = v.getTag(R.id.tag_font_weight) as? Int
            val w = fixed ?: if (v.typeface?.isBold == true) STRONG else REGULAR
            val cur = v.typeface
            val target = face(v.context, w)
            if (cur !== target) v.typeface = target
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) apply(v.getChildAt(i))
    }
}

fun TextView.weight(w: Int): TextView { Fonts.weight(this, w); return this }

/** Замена `setTypeface(null, BOLD/NORMAL)`: сохраняет Onest. Жирный = 650 как у основных заголовков прототипа. */
fun TextView.setOnest(bold: Boolean) {
    Fonts.weight(this, if (bold) Fonts.STRONG else Fonts.REGULAR)
}
