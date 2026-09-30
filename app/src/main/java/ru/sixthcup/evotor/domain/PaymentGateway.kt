package ru.sixthcup.evotor.domain

import android.app.Activity
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CartTotals
import ru.sixthcup.evotor.data.ClientCard

/**
 * Fiscal payment goes ONLY through Evotor core (54-FZ).
 * Docs:
 * - https://developer.evotor.ru/docs/doc_java_receipt_creation.html
 * - https://developer.evotor.ru/docs/doc_java_in_app_receipt_payment.html
 * - https://developer.evotor.ru/docs/doc_java_discounts.html
 */
interface PaymentGateway {
    /**
     * Opens sell receipt with positions (prices already include loyalty)
     * and navigates to Evotor payment UI (cash / card).
     * Callback on UI thread.
     */
    fun charge(
        activity: Activity,
        cart: Cart,
        totals: CartTotals,
        card: ClientCard?,
        applyFree: Boolean,
        callback: (PaymentResult) -> Unit
    )
}

sealed class PaymentResult {
    /** Receipt opened; user is on Evotor payment screen — print happens there */
    data class OpenedForPayment(val message: String) : PaymentResult()
    data class Err(val message: String) : PaymentResult()
}
