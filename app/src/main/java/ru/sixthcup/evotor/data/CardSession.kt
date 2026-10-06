package ru.sixthcup.evotor.data

import android.content.Context

/** Per-sale in-memory session. Expires automatically so an abandoned check cannot leak to the next guest. */
object CardSession {
    private const val TTL_MS = 2 * 60 * 1000L
    @Volatile private var value: String? = null
    @Volatile private var savedAt: Long = 0

    @Synchronized
    fun set(@Suppress("UNUSED_PARAMETER") ctx: Context, token: String) {
        value = token.trim().takeIf { it.isNotBlank() }
        savedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun get(@Suppress("UNUSED_PARAMETER") ctx: Context): String? {
        val current = value
        if (current == null) return null
        if (System.currentTimeMillis() - savedAt > TTL_MS) {
            value = null
            savedAt = 0
            return null
        }
        return current
    }

    @Synchronized
    fun clear(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        value = null
        savedAt = 0
    }
}
