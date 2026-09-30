package ru.sixthcup.evotor.data

import android.util.Base64
import org.json.JSONObject

object CardParser {
    /**
     * Token: base64url(json).sig — same idea as web PWA.
     * Demo: if parse fails, return demo card so barista can train.
     */
    fun parse(token: String): ClientCard {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return demo()
        return try {
            val payload = trimmed.substringBefore('.')
            val pad = when (payload.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            val json = String(
                Base64.decode(
                    payload.replace('-', '+').replace('_', '/') + pad,
                    Base64.DEFAULT
                )
            )
            val o = JSONObject(json)
            ClientCard(
                userId = o.optInt("u", o.optInt("userId", 1)),
                paidTotal = o.optInt("p", o.optInt("paid_total", 0)),
                freeUsed = o.optInt("f", o.optInt("free_used", 0)),
                cashbackRub = o.optInt("cb", o.optInt("cashback", 0)),
                cupsForFree = o.optInt("n", 5),
                couponPercent = o.optInt("cp", -1).takeIf { it >= 0 },
                couponFixedRub = o.optInt("cf", -1).takeIf { it >= 0 },
                rawToken = trimmed
            )
        } catch (_: Exception) {
            // Manual short codes for training on terminal without backend
            when {
                trimmed.equals("DEMO", true) -> ClientCard(1, 5, 0, 50, couponPercent = 10, rawToken = trimmed)
                trimmed.equals("FREE", true) -> ClientCard(2, 5, 0, 0, rawToken = trimmed)
                trimmed.equals("CB", true) -> ClientCard(3, 2, 0, 150, rawToken = trimmed)
                else -> ClientCard(9, 2, 0, 0, rawToken = trimmed)
            }
        }
    }

    fun demo() = ClientCard(1, 4, 0, 30, couponPercent = null, rawToken = "")
}
