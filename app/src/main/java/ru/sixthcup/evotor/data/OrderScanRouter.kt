package ru.sixthcup.evotor.data

/**
 * Split scanner payloads: order QR vs loyalty card.
 * Order QR: ord1.<payload>.<signature>
 * Loyalty: everything else (existing flow).
 */
object OrderScanRouter {
    fun isOrderQr(raw: String): Boolean {
        val t = raw.trim()
        return t.startsWith("ord1.") && t.count { it == '.' } >= 2
    }

    /**
     * Extract oid from ord1 payload without verifying signature (server verifies).
     * Returns null if payload is malformed.
     */
    fun extractOid(raw: String): String? {
        if (!isOrderQr(raw)) return null
        val parts = raw.trim().split('.')
        if (parts.size < 3) return null
        return try {
            val json = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
            val oidKey = "\"oid\""
            val idx = json.indexOf(oidKey)
            if (idx < 0) return null
            val after = json.substring(idx + oidKey.length)
            val start = after.indexOf('"')
            if (start < 0) return null
            val rest = after.substring(start + 1)
            val end = rest.indexOf('"')
            if (end < 0) return null
            rest.substring(0, end)
        } catch (_: Throwable) {
            null
        }
    }
}
