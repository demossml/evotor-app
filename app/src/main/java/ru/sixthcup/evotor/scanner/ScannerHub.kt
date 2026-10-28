package ru.sixthcup.evotor.scanner

import java.util.concurrent.atomic.AtomicReference

/** Last barcode while MainActivity is not ready; consumed onResume / after resolve. */
object ScannerHub {
    private val pending = AtomicReference<String?>(null)

    fun offer(code: String) {
        val t = code.trim()
        if (t.isNotEmpty()) pending.set(t)
    }

    fun poll(): String? = pending.getAndSet(null)

    fun peek(): String? = pending.get()
}
