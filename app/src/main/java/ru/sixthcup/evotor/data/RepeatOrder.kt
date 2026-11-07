package ru.sixthcup.evotor.data

/**
 * План кнопки «Повторить» на пустом чеке.
 *
 * Прошлый чек не восстанавливается «как есть»: это обходило бы обязательные схемы молока
 * и объёма, а платные добавки попадали бы в чек без проверки uuid в номенклатуре.
 *
 * - [Action.Restore] — только товар, у которого сервер уже подтвердил отсутствие схем;
 * - [Action.NeedsChoice] — товар проходит тот же путь, что при обычном нажатии на карточку
 *   (запрос конфигурации → окно выбора объёма/молока/добавок);
 * - [Action.Skip] — строка не восстановима: платная опция (её выбирают заново), комбо-группа
 *   или товар, которого больше нет в каталоге.
 */
object RepeatOrder {

    sealed class Action {
        data class Restore(val line: CartLine) : Action()
        data class NeedsChoice(val product: CatalogProduct) : Action()
        data class Skip(val productUuid: String, val label: String, val reason: String) : Action()
    }

    data class Plan(
        val restore: List<CartLine>,
        val needsChoice: List<CatalogProduct>,
        val skipped: List<Action.Skip>,
    ) {
        val isEmpty: Boolean get() = restore.isEmpty() && needsChoice.isEmpty()
    }

    /**
     * @param noConfigConfirmed проверенное сервером «у товара нет схем» для этого uuid
     */
    fun plan(
        lastOrder: List<CartLine>,
        catalog: List<CatalogProduct>,
        noConfigConfirmed: (String) -> Boolean = { false },
    ): Plan {
        val restore = ArrayList<CartLine>(lastOrder.size)
        val needsChoice = ArrayList<CatalogProduct>(lastOrder.size)
        val skipped = ArrayList<Action.Skip>(lastOrder.size)
        val seen = HashSet<String>()

        for (line in lastOrder) {
            val uuid = line.productUuid
            when {
                uuid.isBlank() || !Cart.isSellable(uuid) -> skipped.add(
                    Action.Skip(uuid, line.name, "синтетическая позиция без товара Эвотор"),
                )
                // Платное молоко и добавки — отдельные строки: восстановить их автоматически
                // нельзя, их выбор проверяется в окне товара по каталогу.
                line.optionId != null -> skipped.add(
                    Action.Skip(uuid, line.name, "опция выбирается заново"),
                )
                line.comboGroupId != null -> skipped.add(
                    Action.Skip(uuid, line.name, "комбо собирается заново"),
                )
                else -> {
                    val product = catalog.firstOrNull { it.uuid == uuid }
                    when {
                        product == null -> skipped.add(
                            Action.Skip(uuid, line.name, "товара нет в каталоге"),
                        )
                        // Одна плитка = один товар: повторный размер той же карточки не дублируем.
                        !seen.add(product.uuid) -> Unit
                        // Строка с выбором (корица) не восстанавливается: выбор делается заново.
                        noConfigConfirmed(uuid) && line.choiceLabel == null -> restore.add(line)
                        else -> needsChoice.add(product)
                    }
                }
            }
        }
        return Plan(restore, needsChoice, skipped)
    }
}
