package ru.sixthcup.evotor.data

/**
 * Окно выбора льготы показываем **ровно один раз на привязку карты**.
 *
 * Флаг ставится до показа окна: повторный `resolve` того же кода, `safeRender` и переход
 * к оплате второй раз окно не откроют. Новая карта (другой QR/код) или отвязка карты
 * сбрасывают отметку.
 */
class BenefitPromptGuard {
    private var askedFor: String? = null

    /**
     * @return true, если окно нужно показать сейчас.
     * Карта помечается опрошенной в любом случае — даже когда льгот нет,
     * чтобы не пересчитывать это на каждый рендер.
     */
    fun ask(cardCode: String, hasOffer: Boolean): Boolean {
        if (cardCode.isEmpty()) return false
        if (askedFor == cardCode) return false
        askedFor = cardCode
        return hasOffer
    }

    /** Карта отвязана: для следующей карты окно снова разрешено. */
    fun onCardCleared() {
        askedFor = null
    }

    /** Диагностика для логов: для какого кода карты окно уже показывали. */
    fun askedCode(): String? = askedFor
}
