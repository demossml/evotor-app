package ru.sixthcup.evotor.sell

import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.FiscalLine
import java.math.BigDecimal

/** Товар номенклатуры терминала, нужный для фискальной позиции: цена в чеке — его цена. */
data class TerminalItem(val productUuid: String, val price: BigDecimal?)

/**
 * Позиция чека: ровно то, что уходит в `Position.Builder` у [SellLauncher].
 * Позиции не склеиваются: скидочная единица приходит отдельной строкой из [FiscalLine].
 */
data class ReceiptPosition(
    val productUuid: String,
    val name: String,
    val quantity: BigDecimal,
    /** Цена, которую ставим в чек (цена терминала, иначе цена строки корзины). */
    val price: BigDecimal,
    /** `setPriceWithDiscountPosition` — только когда цена реально ниже. */
    val discountPrice: BigDecimal?,
    /** Товар найден в номенклатуре Эвотор (иначе позиция «ручная», без НДС/типа). */
    val inTerminal: Boolean,
)

/**
 * Сборка позиций чека из корзины. Чистый Kotlin: продажа вызывает [plan], тесты проверяют
 * тот же код, что работает на кассе, а не отдельный вспомогательный метод.
 *
 * Правила:
 * 1. позиция обязана иметь реальный uuid Эвотор (`mod:…` и пустые uuid не продаются);
 * 2. цена — из номенклатуры терминала, расхождение с каталогом только фиксируется;
 * 3. цена со скидкой ставится лишь если она больше нуля и ниже цены позиции (пол 1 ₽ в [ru.sixthcup.evotor.data.FiscalCalc]);
 * 4. количество, uuid и состав строк не меняются — их собрал путь «В чек».
 */
object ReceiptPlan {

    data class Result(
        val positions: List<ReceiptPosition>,
        /** Позиции без товара Эвотор (синтетические добавки) — в чек не идут. */
        val skippedSynthetic: Int,
        /** Позиции, которых нет в номенклатуре терминала: уйдут «ручной» строкой. */
        val missingInTerminal: Int,
        /** uuid, у которых цена терминала разошлась с ценой корзины. */
        val priceMismatch: List<String>,
    )

    fun plan(fiscalLines: List<FiscalLine>, terminal: (String) -> TerminalItem?): Result {
        val positions = ArrayList<ReceiptPosition>(fiscalLines.size + 2)
        var skippedSynthetic = 0
        var missing = 0
        val mismatch = ArrayList<String>(2)

        for (line in fiscalLines) {
            if (line.productUuid.isBlank() || !Cart.isSellable(line.productUuid)) {
                skippedSynthetic++
                continue
            }
            val item = terminal(line.productUuid)
            val terminalPrice = item?.price
            if (item == null) {
                missing++
            } else if (terminalPrice != null && terminalPrice.compareTo(line.unitPrice) != 0) {
                mismatch.add(line.productUuid)
            }
            val price = terminalPrice ?: line.unitPrice
            val discounted = line.unitPriceWithDiscount
                ?.takeIf { it > BigDecimal.ZERO && it < price }
            positions.add(
                ReceiptPosition(
                    productUuid = line.productUuid,
                    name = line.name,
                    quantity = line.quantity,
                    price = price,
                    discountPrice = discounted,
                    inTerminal = item != null,
                ),
            )
        }
        return Result(positions, skippedSynthetic, missing, mismatch)
    }
}
