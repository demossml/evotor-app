package ru.sixthcup.evotor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Загрузка миниатюр для кассы (вместо Coil/Glide, см. docs/CASHIER-PHOTOS.md: на ST5 важен размер APK и один
 * предсказуемый путь, а нужны только HTTPS → круглая/скруглённая миниатюра).
 *
 * Конвейер:
 *  1. память: LruCache по «url@px» (≈1/12 кучи, ARGB_8888, но картинки ≤ 288 px → десятки КБ);
 *  2. диск: «мастер» каждого URL — WebP, длинная сторона ≤ MASTER_PX (288), в cacheDir/thumbs, лимит DISK_LIMIT_BYTES;
 *  3. сеть: только https, таймауты 4/8 с, потолок размера ответа MAX_BYTES, декод с inSampleSize (полный
 *     кадр в память не попадает), результат ужимается до мастера и пишется на диск.
 * Отмена: каждый запрос — Request с флагом; при переиспользовании ячейки (ThumbView.bind) старый запрос
 * отменяется, а ожидающая в очереди задача просто пропускается. Неудача запоминается (negative cache) на
 * NEG_TTL_MS, чтобы битая ссылка не дёргала сеть при каждом bind.
 * Все колбэки приходят на главный поток. Декод и сеть — в пуле из 3 потоков, не на UI.
 */
object ThumbLoader {
    private const val MASTER_PX = 288
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val DISK_LIMIT_BYTES = 24L * 1024 * 1024
    private const val NEG_TTL_MS = 5 * 60 * 1000L

    class Request(val url: String, val px: Int, val target: ThumbView) {
        @Volatile var cancelled = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, LinkedBlockingDeque<Runnable>()).apply {
        allowCoreThreadTimeOut(true)
    }
    private val locks = ConcurrentHashMap<String, Any>()
    private val failed = ConcurrentHashMap<String, Long>()
    @Volatile private var dir: File? = null

    private val mem: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        max(1024, (Runtime.getRuntime().maxMemory() / 1024 / 12).toInt())
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.byteCount / 1024)
    }

    /** Вызывать один раз из Application.onCreate. */
    fun init(context: Context) {
        val d = File(context.applicationContext.cacheDir, "thumbs")
        d.mkdirs()
        dir = d
        pool.execute { prune(d) }
    }

    /** Показать миниатюру в view: из памяти — сразу, иначе — в фоне. Размер px — целевая сторона view. */
    fun load(view: ThumbView, url: String?, px: Int): Request? {
        val u = url?.trim().orEmpty()
        if (!u.startsWith("https://", ignoreCase = true)) { view.setBitmap(null); return null }
        val key = "$u@$px"
        mem.get(key)?.let { view.setBitmap(it); return null }
        view.setBitmap(null)
        val bad = failed[u]
        if (bad != null && System.currentTimeMillis() - bad < NEG_TTL_MS) return null
        val req = Request(u, px, view)
        pool.execute {
            if (req.cancelled) return@execute
            val bmp = try { fetch(u, px) } catch (_: Throwable) { null }
            if (bmp == null) failed[u] = System.currentTimeMillis() else {
                failed.remove(u)
                try { mem.put(key, bmp) } catch (_: Throwable) {}
            }
            main.post { if (!req.cancelled && bmp != null) req.target.setBitmap(bmp) }
        }
        return req
    }

    private fun fetch(url: String, px: Int): Bitmap? {
        val d = dir
        val file = d?.let { File(it, sha1(url) + ".webp") }
        if (file != null && file.isFile && file.length() > 0) {
            decode(file, px)?.let { file.setLastModified(System.currentTimeMillis()); return it }
            file.delete()
        }
        // Один поток качает конкретный URL; остальные ждут и затем берут файл с диска.
        val lock = locks.getOrPut(url) { Any() }
        synchronized(lock) {
            try {
                if (file != null && file.isFile && file.length() > 0) decode(file, px)?.let { return it }
                val bytes = download(url) ?: return null
                val master = decodeBytes(bytes, MASTER_PX) ?: return null
                if (file != null) writeMaster(file, master)
                return if (px >= master.width && px >= master.height) master else decodeBytesFrom(master, px)
            } finally { /* лок не удаляем: иначе ожидающие потоки получат другой объект и скачают URL параллельно */ }
        }
    }

    private fun download(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/webp,image/*;q=0.8")
            }
            if (conn.responseCode !in 200..299) return null
            val len = conn.contentLength
            if (len > MAX_BYTES) return null
            val out = java.io.ByteArrayOutputStream(if (len > 0) len else 32 * 1024)
            conn.inputStream.use { ins ->
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        } catch (_: Throwable) { null } finally { try { conn?.disconnect() } catch (_: Throwable) {} }
    }

    private fun decodeBytes(bytes: ByteArray, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, maxSide) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return fit(bmp, maxSide)
    }

    private fun decode(file: File, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, px) }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun decodeBytesFrom(master: Bitmap, px: Int): Bitmap = fit(master, px)

    /** Степень двойки так, чтобы после неё меньшая сторона всё ещё ≥ maxSide (не мутим сильнее нужного). */
    private fun sample(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (w / (s * 2) >= maxSide && h / (s * 2) >= maxSide) s *= 2
        return s
    }

    private fun fit(b: Bitmap, maxSide: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxSide) return b
        val k = maxSide.toFloat() / longest
        val out = Bitmap.createScaledBitmap(b, max(1, (b.width * k).toInt()), max(1, (b.height * k).toInt()), true)
        if (out !== b) b.recycle()
        return out
    }

    @Suppress("DEPRECATION")
    private fun writeMaster(file: File, bmp: Bitmap) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use { os ->
                val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                bmp.compress(fmt, 82, os)
            }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: Throwable) { tmp.delete() }
    }

    private fun prune(d: File) {
        try {
            val files = d.listFiles()?.filter { it.isFile } ?: return
            files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
            var total = files.filter { !it.name.endsWith(".tmp") }.sumOf { it.length() }
            if (total <= DISK_LIMIT_BYTES) return
            for (f in files.filter { !it.name.endsWith(".tmp") }.sortedBy { it.lastModified() }) {
                total -= f.length(); f.delete()
                if (total <= DISK_LIMIT_BYTES * 3 / 4) break
            }
        } catch (_: Throwable) {}
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
