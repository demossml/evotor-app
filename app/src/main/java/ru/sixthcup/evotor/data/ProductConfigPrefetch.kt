package ru.sixthcup.evotor.data

import android.content.Context
import android.util.Log
import ru.sixthcup.evotor.net.ProductConfigApi
import kotlin.concurrent.thread

/**
 * Какие товары догружать заранее.
 *
 * Правила: без дублей, только валидные `productId`, пропускаем то, что уже лежит
 * в кэше, не больше лимита за один проход. Чистая функция — покрыта тестом.
 */
object ProductConfigPrefetchPlan {
    fun ids(candidates: List<Int>, limit: Int = 40, cached: (Int) -> Boolean): List<Int> {
        if (limit <= 0) return emptyList()
        val out = LinkedHashSet<Int>()
        for (id in candidates) {
            if (out.size >= limit) break
            if (id <= 0) continue
            if (cached(id)) continue
            out.add(id)
        }
        return out.toList()
    }
}

/**
 * Фоновая предзагрузка конфигураций (молоко/добавки/корица) после обновления каталога.
 *
 * Зачем: лист товара открывается по тапу, и если конфигурация уже в кэше —
 * он показывается сразу в полном виде, без «пустого» первого рендера и пересборки.
 * Ошибки и 404 — только в лог, на продажу не влияют.
 */
object ProductConfigPrefetcher {
    private const val TAG = "ConfigPrefetch"
    private const val GAP_MS = 150L

    @Volatile
    private var running = false

    fun start(ctx: Context, storeUuid: String, candidates: List<Int>) {
        if (storeUuid.isBlank() || candidates.isEmpty()) return
        if (running) {
            Log.i(TAG, "prefetch_skip busy")
            return
        }
        running = true
        val app = ctx.applicationContext
        thread(name = "config-prefetch") {
            try {
                val ids = ProductConfigPrefetchPlan.ids(
                    candidates,
                    cached = { id -> ProductConfigCache.load(app, storeUuid, id) != null },
                )
                Log.i(TAG, "prefetch_start count=${ids.size} candidates=${candidates.size}")
                ids.forEachIndexed { index, id ->
                    val res = try {
                        ProductConfigApi.fetch(id, storeUuid)
                    } catch (t: Throwable) {
                        Log.w(TAG, "prefetch_fetch_failed id=$id", t)
                        null
                    }
                    res?.config?.let { ProductConfigCache.save(app, it) }
                    // 404 — маршрут на backend не поднят: дальше дёргать бессмысленно.
                    if (res?.unsupported == true) {
                        Log.i(TAG, "prefetch_unsupported")
                        return@thread
                    }
                    if (index < ids.size - 1) {
                        try {
                            Thread.sleep(GAP_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@thread
                        }
                    }
                }
                Log.i(TAG, "prefetch_done requested=${ids.size}")
            } catch (t: Throwable) {
                Log.w(TAG, "prefetch_failed", t)
            } finally {
                running = false
            }
        }
    }
}
