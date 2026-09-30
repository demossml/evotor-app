package ru.sixthcup.evotor.domain

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.ClientCard
import ru.sixthcup.evotor.data.LoyaltyRules
import java.util.UUID

object LoyaltyReceiptFactory {
    /**
     * Builds loyalty payload after successful fiscal payment.
     * Production: sign with device Ed25519 key (web-compatible).
     */
    fun create(
        card: ClientCard?,
        cart: Cart,
        applyFree: Boolean,
        cashbackUseRub: Int,
        amountRub: Int,
        fiscalId: String
    ): String {
        val paidDrinks = LoyaltyRules.paidDrinks(cart, applyFree)
        val df = if (applyFree && cart.freeLine() != null) 1 else 0
        val items = JSONArray()
        cart.snapshot().forEach { line ->
            items.put(
                JSONObject()
                    .put("id", line.product.id)
                    .put("name", line.product.name)
                    .put("qty", line.qty)
                    .put("free", line.isFree && applyFree)
                    .put("price", line.product.priceRub)
            )
        }
        val payload = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("u", card?.userId ?: 0)
            .put("dp", paidDrinks)
            .put("df", df)
            .put("dcb", cashbackUseRub)
            .put("amount", amountRub)
            .put("fiscal", fiscalId)
            .put("items", items)
            .put("ts", System.currentTimeMillis())
        val body = Base64.encodeToString(
            payload.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        // demo signature placeholder
        return "$body.demo"
    }
}
