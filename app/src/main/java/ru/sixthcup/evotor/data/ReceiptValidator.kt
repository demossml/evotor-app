package ru.sixthcup.evotor.data

/**
 * Проблема позиции корзины перед отправкой в Эвотор.
 * [remove] = true — позицию нельзя провести в чеке, её нужно убрать из корзины.
 */
data class ReceiptIssue(
    val cartKey: String,
    val name: String,
    val reason: String,
    val message: String,
)

/**
 * Сверка корзины с последним полученным каталогом перед «В чек».
 *
 * Контракт: платная опция без evotorUuid не попадает в чек ни при каких условиях;
 * если схему добавок изменили в админке (опция пропала / стала available=false / получила
 * другой evotorUuid), позицию не отправляем, показываем сообщение и обновляем каталог.
 * Удалённый в Эвоторе товар (404 по uuid) — тот же путь: сообщение + обновление каталога.
 */
object ReceiptValidator {

    fun validate(lines: List<CartLine>, products: List<CatalogProduct>): List<ReceiptIssue> {
        if (lines.isEmpty()) return emptyList()
        val byUuid = products.associateBy { it.uuid }
        val toppingsById = HashMap<Int, Topping>()
        products.forEach { p -> p.toppings.forEach { t -> if (!toppingsById.containsKey(t.id)) toppingsById[t.id] = t } }

        val out = ArrayList<ReceiptIssue>(2)
        for (line in lines) {
            if (!Cart.isSellable(line.productUuid)) {
                out.add(issue(line, "no_evotor_product", "У позиции нет товара Эвотор — в чек она не попадёт."))
                continue
            }
            if (byUuid[line.productUuid] == null) {
                out.add(issue(line, "product_not_found", "Товар удалён или недоступен в Эвоторе на этой точке."))
                continue
            }
            val optionId = line.optionId ?: continue
            val topping = toppingsById[optionId]
            if (topping == null) {
                out.add(issue(line, "option_removed", "Добавка больше не входит в схему — обновите каталог."))
                continue
            }
            if (!topping.available) {
                out.add(issue(line, "option_unavailable", "Добавка недоступна в кассе."))
                continue
            }
            if (topping.priceRub.signum() > 0 && topping.evotorUuid.isNullOrBlank()) {
                out.add(issue(line, "option_without_uuid", "Платная добавка не выгружена в Эвотор — её нельзя продать."))
                continue
            }
            if (!topping.zeroOption && topping.evotorUuid != line.productUuid) {
                out.add(issue(line, "option_changed", "Добавка изменилась в админке — обновите каталог и выберите заново."))
            }
        }
        return out
    }

    private fun issue(line: CartLine, reason: String, message: String) = ReceiptIssue(
        cartKey = line.cartKey.ifBlank { line.productUuid },
        name = line.name,
        reason = reason,
        message = message,
    )
}
