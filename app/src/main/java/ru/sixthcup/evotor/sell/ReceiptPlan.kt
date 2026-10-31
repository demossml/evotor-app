package ru.sixthcup.evotor.sell

import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.data.CartLine
import ru.sixthcup.evotor.data.FiscalLine
import java.math.BigDecimal

/** Товар номенклатуры терминала, нужный для фискальной позиции: цена в чеке — его цена. */
data class TerminalItem(val productUuid: String, val price: BigDecimal?)

/** Строка корзины, для которой в терминале нет товара: позиция не создаётся, чек блокируется. */
data class MissingProduct(val productUuid: String, val name: String)

/**
 * Позиция чека: ровно то, что уходит в `Position.Builder` у [SellLauncher].
 * Создаётся только для товара, найденного в номенклатуре Эвотор.
 */
data class ReceiptPosition(
    val productUuid: String,
    val name: String,
    val quantity: BigDecimal,
    /** Цена, которую ставим в чек (цена терминала, иначе цена строки корзины). */
    val price: BigDecimal,
    /** `setPriceWithDiscountPosition` — только когда цена реально ниже. */
    val discountPrice: BigDecimal?,
)

/**
 * Сборка позиций чека из корзины. Чистый Kotlin: продажа вызывает [plan] и [blockReason],
 * тесты проверяют тот же код, что работает на кассе.
 *
 * Правила:
 * 1. позиция обязана иметь реальный uuid Эвотор (`mod:…` и пустые uuid не продаются);
 * 2. товар обязан существовать в номенклатуре терминала: «ручную» позицию
 *    (`newInstance(uuid, productUuid, name, Measure, …)`) не создаём — SDK ставит ей
 *    `ProductType.NORMAL` и `TaxNumber = null`, фискальные признаки не гарантированы.
 *    Такой товар попадает в [Result.missing], а чек блокируется;
 * 3. цена — из номенклатуры терминала, расхождение с каталогом только фиксируется;
 * 4. цена со скидкой ставится лишь если она больше нуля и ниже цены позиции
 *    (пол 1 ₽ в [ru.sixthcup.evotor.data.FiscalCalc]).
 */
object ReceiptPlan {

    data class Result(
        val positions: List<ReceiptPosition>,
        /** Строки без товара в терминале — чек открывать нельзя (частичной продажи нет). */
        val missing: List<MissingProduct>,
        /** Позиции без товара Эвотор (синтетические добавки) — в чек не идут. */
        val skippedSynthetic: Int,
        /** uuid, у которых цена терминала разошлась с ценой корзины. */
        val priceMismatch: List<String>,
    )

    fun plan(fiscalLines: List<FiscalLine>, terminal: (String) -> TerminalItem?): Result {
        val positions = ArrayList<ReceiptPosition>(fiscalLines.size + 2)
        val missing = ArrayList<MissingProduct>(2)
        var skippedSynthetic = 0
        val mismatch = ArrayList<String>(2)

        for (line in fiscalLines) {
            if (line.productUuid.isBlank() || !Cart.isSellable(line.productUuid)) {
                skippedSynthetic++
                continue
            }
            val item = terminal(line.productUuid)
            if (item == null) {
                // Товара нет в номенклатуре: не выдумываем позицию, а блокируем чек.
                if (missing.none { it.productUuid == line.productUuid }) {
                    missing.add(MissingProduct(line.productUuid, line.name))
                }
                continue
            }
            val terminalPrice = item.price
            if (terminalPrice != null && terminalPrice.compareTo(line.unitPrice) != 0) {
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
                ),
            )
        }
        return Result(positions, missing, skippedSynthetic, mismatch)
    }

    /**
     * Текст для кассира: чего нет в номенклатуре (с uuid — по нему ищут товар в Эвоторе).
     */
    fun missingMessage(missing: List<MissingProduct>): String =
        "Нет товара в кассе: " +
            missing.joinToString(", ") { "${it.name} (${it.productUuid})" } +
            ". Обновите каталог Эвотор."

    /**
     * Отдельные uuid строк корзины, которые надо проверить в номенклатуре терминала
     * до резервирования номера очереди. Синтетические (`mod:…`) и пустые не проверяются —
     * они всё равно не попадут в чек.
     */
    fun cartUuids(lines: List<CartLine>): List<String> {
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            val uuid = line.productUuid
            if (uuid.isBlank() || !Cart.isSellable(uuid)) continue
            if (out.contains(uuid)) continue
            out.add(uuid)
        }
        return out
    }

    /**
     * Причина, по которой чек открывать нельзя. `null` — можно.
     * Сообщение содержит uuid отсутствующего товара: кассир видит, что именно обновить.
     */
    fun blockReason(result: Result): String? {
        if (result.missing.isNotEmpty()) return missingMessage(result.missing)
        if (result.skippedSynthetic > 0) {
            return "В чеке есть позиция без товара Эвотор — удалите её из корзины."
        }
        if (result.positions.isEmpty()) return "Корзина пуста"
        return null
    }
}
