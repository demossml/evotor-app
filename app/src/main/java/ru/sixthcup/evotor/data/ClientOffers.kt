package ru.sixthcup.evotor.data

/**
 * Что можно предложить гостю в окне клиента. Чистая модель: правила видны и тестируются без Android.
 * Подарок, ваучер и кэшбэк при выборе взаимоисключающие ([Choice.normalize]); здесь решается только,
 * что показывать. Кэшбэк показывается ВСЕГДА, когда он доступен по резерву, в том числе при ваучерах:
 * в нём лежат реферальные начисления гостя.
 */
data class ClientOffers(
    val gift: Boolean,
    val cashback: Boolean,
    val vouchers: List<ResolveVoucher>,
) {
    /** Есть ли хоть одна льгота. Нет — окно показывает пояснение вместо вариантов. */
    val any: Boolean get() = gift || cashback || vouchers.isNotEmpty()

    companion object {
        fun of(card: CardInfo, nowSec: Long): ClientOffers {
            val reserved = DiscountCalc.reserved(card, nowSec)
            val secure = CardSession.hasSecureProof(card)
            val voucherRedeemable = card.canRedeem && reserved && secure && CardSession.effectiveKind(card) == "token"
            return ClientOffers(
                gift = card.canRedeem && card.freeAvailable > 0 && reserved && secure,
                cashback = CardSession.cashbackRedeemable(card, reserved) && card.cashbackKopecks > 0,
                vouchers = if (voucherRedeemable) card.vouchers else emptyList(),
            )
        }
    }
}
