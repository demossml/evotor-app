package ru.sixthcup.evotor.data

import ru.sixthcup.evotor.net.OrderApi
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Льгота гостя, выбранная в предзаказе (PWA). Backend уже применил её к заказу и держит
 * резерв лояльности по `orderId`; касса только снимает с чека ровно эту сумму и пишет
 * `sc.preorder`. Правило не пересчитывается на клиенте.
 *
 * При активной льготе заказа действует **одно правило за раз**: карточные `free`/`cb`/ваучер
 * в этот чек не идут (см. SellLauncher и DiscountIntegrationService).
 */
data class OrderBenefit(
    /** `gift` | `voucher` | `cashback` (как пришло с backend). */
    val kind: String,
    val title: String,
    val amountRub: BigDecimal,
) {
    val hasAmount: Boolean get() = amountRub.signum() > 0

    /** Подпись для кассира: «Бонус: <title> −300 ₽». */
    fun label(): String {
        val name = title.trim().ifBlank { PreorderQueue.benefitKindLabel(kind) }
        return if (hasAmount) "Бонус: $name −${rub(amountRub)} ₽" else "Бонус: $name"
    }

    companion object {
        /**
         * Из ответа API. Берём только когда backend подтвердил правило целиком:
         * есть `benefit`, есть `quoteTotal` и сумма больше нуля.
         */
        fun from(o: OrderApi.Order): OrderBenefit? {
            if (o.quoteTotal == null) return null
            val b = o.benefit ?: return null
            val rub = BigDecimal(b.amountRub.coerceAtLeast(0)).setScale(2, RoundingMode.DOWN)
            if (rub.signum() <= 0) return null
            return OrderBenefit(kind = b.kind.trim().lowercase(), title = b.title.trim(), amountRub = rub)
        }

        /** Восстановление из сессии (SharedPreferences) после перезапуска процесса. */
        fun restore(kind: String?, title: String?, rub: String?): OrderBenefit? {
            if (kind.isNullOrBlank() && title.isNullOrBlank()) return null
            val amount = try {
                BigDecimal((rub ?: return null).trim())
            } catch (_: Throwable) {
                return null
            }
            if (amount.signum() <= 0) return null
            return OrderBenefit(kind.orEmpty(), title.orEmpty(), amount.setScale(2, RoundingMode.DOWN))
        }

        private fun rub(v: BigDecimal): String =
            v.setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
    }
}
