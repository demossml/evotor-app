package ru.sixthcup.evotor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.max

/**
 * Одна лёгкая view вместо «FrameLayout + TextView + ImageView + clipToOutline» на каждую строку:
 * рисует фон, круг/скруглённый прямоугольник с фото (BitmapShader, центр-кроп) либо инициалы.
 * Без clipToOutline и без дополнительных слоёв — дёшево при скролле на слабом GPU.
 * Размеры: круглый режим — radiusDp ≥ половины стороны.
 */
class ThumbView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEEF4FF.toInt() } // brand_soft
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF002FA7.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER // brand
    }
    private val rect = RectF()
    private val m = Matrix()
    private var bitmap: Bitmap? = null
    private var label: String = ""
    private var radiusPx = 0f
    private var request: ThumbLoader.Request? = null

    /** Радиус скругления в dp; большое значение (≥ сторона/2) даёт круг. */
    fun setCorner(dp: Int) { radiusPx = dp * density; invalidate() }

    /** Привязать товар: инициалы как заглушка + загрузка по url. Предыдущий запрос отменяется. */
    fun bind(name: String, url: String?, px: Int) {
        request?.cancelled = true
        request = null
                // Brand placeholder (PWA parity), not product initials
        label = "6.7"
        contentDescription = name
        request = ThumbLoader.load(this, url, px)
        invalidate()
    }

    /** Освободить ячейку (onViewRecycled): отмена загрузки и сброс картинки. */
    fun release() {
        request?.cancelled = true
        request = null
        setBitmap(null)
    }

    fun setBitmap(b: Bitmap?) {
        if (bitmap === b) return
        bitmap = b
        photo.shader = if (b != null) BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) else null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        rect.set(0f, 0f, w, h)
        val r = radiusPx.coerceAtMost(max(w, h) / 2f)
        val b = bitmap
        if (b != null) {
            val k = max(w / b.width, h / b.height)
            m.reset()
            m.setScale(k, k)
            m.postTranslate((w - b.width * k) / 2f, (h - b.height * k) / 2f)
            photo.shader?.setLocalMatrix(m)
            canvas.drawRoundRect(rect, r, r, photo)
        } else {
            canvas.drawRoundRect(rect, r, r, bg)
            txt.textSize = (if (w < 40 * density) 0.36f else 0.3f) * h
            canvas.drawText(label, w / 2f, h / 2f - (txt.descent() + txt.ascent()) / 2f, txt)
        }
    }
}
