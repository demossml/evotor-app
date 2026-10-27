package ru.sixthcup.evotor.net

import android.util.Log
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig
import ru.sixthcup.evotor.data.QueueNumber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Публичный номер заказа с backend (`POST /api/devices/queue-number`).
 *
 * Номер выдаёт только сервер — локальная генерация запрещена (две кассы одной точки
 * получили бы одинаковые номера). Если сервер недоступен, продажа не блокируется:
 * возвращаем `null`, чек печатается без номера.
 */
object QueueNumberApi {
    private const val TAG = "QueueNumberApi"

    fun issue(orderId: String? = null): QueueNumber? {
        val startedAt = System.currentTimeMillis()
        return try {
            val conn = (URL(BuildConfig.API_BASE_URL.trimEnd('/') + "/api/devices/queue-number").openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.connectTimeout = 1200
            conn.readTimeout = 1500
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            DeviceHeaders.apply(conn)
            val body = JSONObject().apply { if (!orderId.isNullOrBlank()) put("orderId", orderId) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            if (status !in 200..299) {
                Log.w(TAG, "queue_number_failed http=$status ms=${System.currentTimeMillis() - startedAt}")
                return null
            }
            QueueNumber.parse(text)?.also {
                Log.i(TAG, "queue_number order=${it.orderId.take(8)} number=${it.publicNumber}")
            }
        } catch (t: Throwable) {
            // Сеть/таймаут: номер не печатаем, продажа продолжается штатно.
            Log.w(TAG, "queue_number_offline ms=${System.currentTimeMillis() - startedAt} err=${t.javaClass.simpleName}")
            null
        }
    }
}
