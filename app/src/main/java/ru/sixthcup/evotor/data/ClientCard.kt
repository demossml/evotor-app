package ru.sixthcup.evotor.data

data class ClientCard(
    val userId: Int,
    val paidTotal: Int,
    val freeUsed: Int,
    val cashbackRub: Int,
    val cupsForFree: Int = 5,
    /** percent 0..100 or null */
    val couponPercent: Int? = null,
    val couponFixedRub: Int? = null,
    val rawToken: String = ""
) {
    val freeAvailable: Int
        get() = LoyaltyRules.freeAvailable(paidTotal, freeUsed, cupsForFree)
    val progress: Int
        get() = LoyaltyRules.progress(paidTotal, cupsForFree)
}
