package ru.sixthcup.evotor.data

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Раскладка льготы между позициями и чеком.
 *
 * `evo.v2.receipt.sell.receiptDiscount` срабатывает не всегда (часто нужен тап иконки на экране
 * оплаты), поэтому подарок и ваучер уходят в чек **на уровне позиций** через
 * `Position.setPriceWithDiscountPosition`. Кэшбэк и комбо остаются скидкой на чек.
 */
data class FiscalSplit(
    /** Скидка, которую касса кладёт в `priceWithDiscountPosition` (подарок + ваучер), копейки. */
    val positionDiscountKopecks: Long,
    /** Скидка на весь чек (кэшбэк), копейки. Уходит в `ReceiptDiscountEventResult`. */
    val receiptDiscountKopecks: Long,
    val giftApplied: Boolean,
    val voucherId: Int?,
    /** Сработал фискальный пол 1 ₽: подарок не может сделать чек нулевым. */
    val floorApplied: Boolean,
) {
    val totalDiscountKopecks: Long get() = positionDiscountKopecks + receiptDiscountKopecks
}

object FiscalCalc {
    /** Подарок в фискальном чеке — минимум 1 ₽ (0 пробивать нельзя). */
    val GIFT_FISCAL_MIN: BigDecimal = BigDecimal("1.00")

    fun split(
        gross: BigDecimal,
        eligibleUnitPrices: List<BigDecimal>,
        card: CardInfo?,
        choice: Choice,
        nowSec: Long,
    ): FiscalSplit {
        val grossKopecks = DiscountCalc.kopecks(gross).coerceAtLeast(0L)
        if (card == null || card.code.isEmpty()) {
            return FiscalSplit(0L, 0L, giftApplied = false, voucherId = null, floorApplied = false)
        }
        val reserved = DiscountCalc.reserved(card, nowSec)
        val voucher = choice.voucherId?.let { id -> card.vouchers.firstOrNull { it.id == id } }
        val calc = DiscountCalc.compute(
            gross = gross,
            eligibleUnitPrices = eligibleUnitPrices,
            reserved = reserved,
            freeChosen = choice.free,
            freeAvailable = card.freeAvailable,
            requestedCashbackKopecks = choice.cashbackKopecks,
            cardCashbackKopecks = card.cashbackKopecks,
            voucher = voucher,
            voucherAllowed = VoucherCalc.allowedTogether(
                voucher,
                DiscountCalc.freeApplies(reserved, choice.free, card.freeAvailable, eligibleUnitPrices),
            ),
        )
        val giftApplied = calc.free.signum() > 0
        val voucherApplied = calc.voucher.signum() > 0
        // Подарок опускает цену ОДНОЙ единицы ровно до 1 ₽ (не до нуля и не «всю линию целиком»).
        val cheapestEligible = eligibleUnitPrices.minOrNull()?.let { DiscountCalc.kopecks(it) } ?: 0L
        val giftDiscount = if (giftApplied) {
            (cheapestEligible - DiscountCalc.GIFT_FISCAL_MIN_KOPECKS).coerceAtLeast(0L)
        } else 0L
        var positionDiscount = (giftDiscount + DiscountCalc.kopecks(calc.voucher)).coerceAtMost(grossKopecks)
        val receiptDiscount = DiscountCalc.kopecks(calc.cashback).coerceAtMost(grossKopecks)

        var floor = false
        if (giftApplied || voucherApplied) {
            if (grossKopecks < DiscountCalc.GIFT_FISCAL_MIN_KOPECKS) {
                // Чек дешевле рубля не раздуваем: скидки нет.
                positionDiscount = 0L
            } else {
                // Страховка: после всех скидок на чеке должно остаться минимум 1 ₽.
                val maxPosition = (grossKopecks - receiptDiscount - DiscountCalc.GIFT_FISCAL_MIN_KOPECKS).coerceAtLeast(0L)
                if (positionDiscount > maxPosition) {
                    positionDiscount = maxPosition
                    floor = true
                }
            }
        }
        if (giftApplied) floor = true
        return FiscalSplit(
            positionDiscountKopecks = positionDiscount,
            receiptDiscountKopecks = receiptDiscount,
            giftApplied = giftApplied,
            voucherId = if (voucherApplied) voucher?.id else null,
            floorApplied = floor,
        )
    }
}

/**
 * Позиция для Эвотора с ценой «как есть» и (опционально) ценой со скидкой.
 * Позиции не склеиваются: подарочная единица выносится отдельной строкой,
 * чтобы скидка легла ровно на неё.
 */
data class FiscalLine(
    val productUuid: String,
    val name: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val unitPriceWithDiscount: BigDecimal?,
    val recipe: String? = null,
) {
    fun total(): BigDecimal = (unitPriceWithDiscount ?: unitPrice).multiply(quantity)
}

object FiscalLines {
    /**
     * Раскладывает скидку [split] по позициям корзины в копейках.
     * Подарок — одна самая дешёвая подходящая единица; остаток (ваучер) — по порядку корзины.
     */
    fun build(lines: List<CartLine>, split: FiscalSplit): List<FiscalLine> {
        val plain = lines.map {
            FiscalLine(it.productUuid, it.name, it.quantity, it.priceRub, null, it.recipe)
        }
        if (split.positionDiscountKopecks <= 0L) return plain

        // Единицы в копейках: подарок применяется к одной единице, ваучер — к остальным по порядку.
        data class Unit(val lineIndex: Int, val priceKopecks: Long) { var discount: Long = 0L }
        val units = ArrayList<Unit>()
        lines.forEachIndexed { idx, line ->
            val qty = line.quantity.setScale(0, RoundingMode.DOWN).toInt().coerceAtLeast(1)
            val priceKopecks = DiscountCalc.kopecks(line.priceRub)
            repeat(qty) { units.add(Unit(idx, priceKopecks)) }
        }
        if (units.isEmpty()) return plain

        var remaining = split.positionDiscountKopecks

        if (split.giftApplied) {
            val giftIdx = giftUnitIndex(units.map { it.priceKopecks }, lines)
            if (giftIdx != null) {
                val unit = units[giftIdx]
                // Подарок: цена единицы опускается до 1 ₽, но не ниже нуля.
                val giftDiscount = (unit.priceKopecks - DiscountCalc.GIFT_FISCAL_MIN_KOPECKS).coerceAtLeast(0L)
                val applied = minOf(giftDiscount, remaining)
                unit.discount = applied
                remaining -= applied
            }
        }
        // Остаток (ваучер) — по порядку, каждая единица не ниже нуля.
        for (u in units) {
            if (remaining <= 0) break
            val room = u.priceKopecks - u.discount
            if (room <= 0) continue
            val take = minOf(room, remaining)
            u.discount += take
            remaining -= take
        }

        // Разворачиваем единицы обратно в позиции: скидочные единицы — отдельными строками.
        val out = ArrayList<FiscalLine>(plain.size + 2)
        lines.forEachIndexed { idx, line ->
            val lineUnits = units.filter { it.lineIndex == idx }
            val full = lineUnits.filter { it.discount == 0L }.size
            if (full > 0) {
                out.add(plain[idx].copy(quantity = BigDecimal(full)))
            }
            lineUnits.filter { it.discount > 0L }.forEach { u ->
                val discounted = u.priceKopecks - u.discount
                out.add(
                    plain[idx].copy(
                        quantity = BigDecimal.ONE,
                        unitPriceWithDiscount = BigDecimal(discounted).divide(BigDecimal(100), 2, RoundingMode.DOWN),
                    ),
                )
            }
        }
        return out
    }

    /** Сумма к оплате по позициям, копейки. Должна совпадать с тем, что покажет касса. */
    fun payableKopecks(list: List<FiscalLine>): Long =
        list.fold(0L) { acc, l -> acc + DiscountCalc.kopecks(l.total()) }

    private fun giftUnitIndex(prices: List<Long>, lines: List<CartLine>): Int? {
        // Самая дешёвая единица среди подарочных позиций (не добавки).
        var best: Int? = null
        var bestPrice = Long.MAX_VALUE
        var offset = 0
        lines.forEachIndexed { idx, line ->
            val qty = line.quantity.setScale(0, RoundingMode.DOWN).toInt().coerceAtLeast(1)
            if (line.freeEligible && line.optionId == null) {
                for (k in 0 until qty) {
                    val i = offset + k
                    if (i < prices.size && prices[i] < bestPrice) {
                        bestPrice = prices[i]; best = i
                    }
                }
            }
            offset += qty
        }
        return best
    }
}
