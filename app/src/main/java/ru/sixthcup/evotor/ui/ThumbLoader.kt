package ru.sixthcup.evotor.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Лёгкая загрузка миниатюр без Coil: HTTPS URL с app.67coffee.ru (тот же хост, что catalog).
 * Кэш в памяти, декод с subsample под размер view. Вызывать с UI-потока bind().
 */
object ThumbLoader {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val inFlight = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        max(4, (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()) // ~1/16 heap in KB
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun bind(view: ImageView, url: String?, placeholder: Int? = null) {
        val tag = url?.trim().orEmpty()
        view.tag = tag
        if (tag.isEmpty() || !tag.startsWith("http", ignoreCase = true)) {
            view.setImageDrawable(null)
            view.visibility = android.view.View.GONE
            return
        }
        view.visibility = android.view.View.VISIBLE
        cache.get(tag)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        val targetW = if (view.width > 0) view.width else 256
        val targetH = if (view.height > 0) view.height else 256

        val cb: (Bitmap?) -> Unit = { bmp ->
            main.post {
                if (view.tag != tag) return@post
                if (bmp != null) view.setImageBitmap(bmp)
            }
        }

        val waiters = inFlight.getOrPut(tag) { mutableListOf() }
        synchronized(waiters) {
            waiters.add(cb)
            if (waiters.size > 1) return // already downloading
        }

        pool.execute {
            val bmp = download(tag, targetW, targetH)
            if (bmp != null) {
                try { cache.put(tag, bmp) } catch (_: Throwable) {}
            }
            val list = inFlight.remove(tag).orEmpty()
            list.forEach { it(bmp) }
        }
    }

    private fun download(url: String, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/*,*/*")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            decodeSampled(bytes, reqW, reqH)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeSampled(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, reqW, reqH)
            inPreferredConfig = Bitmap.Config.RGB_565 // меньше памяти на ST5
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun calculateInSampleSize(o: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val (h, w) = o.outHeight to o.outWidth
        var sample = 1
        if (h > reqH || w > reqW) {
            var halfH = h / 2
            var halfW = w / 2
            while (halfH / sample >= reqH && halfW / sample >= reqW) sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}
