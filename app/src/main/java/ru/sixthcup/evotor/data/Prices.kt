package ru.sixthcup.evotor.data

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Цена товара из ProductTable Эвотор → рубли.
 *
 * Факт из исходников integration-library v0.6.27 (`CursorExt.getMoney`): колонка PRICE_OUT хранит
 * ЦЕЛОЕ число КОПЕЕК, библиотека всегда делит его на 100 (`BigDecimal(money).divide(BigDecimal(100))`).
 * Поэтому режим по умолчанию — KOPECKS: 15000 → 150.00, 150 → 1.50, 0 → 0.00.
 *
 * AUTO — эвристика из ТЗ на случай нестандартной прошивки: целое значение ≥ 1000 считается копейками
 * (15000 → 150.00), остальное — уже рублями (150 → 150.00). Ошибается на товарах дешевле 10 ₽ в копейках
 * и дороже 1000 ₽ в рублях, поэтому по умолчанию выключена.
 * RUBLES — значение уже в рублях.
 * Режим задаётся сборкой: -PPRICE_UNITS=KOPECKS|AUTO|RUBLES (BuildConfig.PRICE_UNITS).
 */
object Prices {
    enum class Units { KOPECKS, AUTO, RUBLES;
        companion object { fun parse(s: String?): Units = try { valueOf(s.orEmpty().trim().uppercase()) } catch (_: Throwable) { KOPECKS } }
    }

    fun priceFromEvotorRaw(raw: Double, units: Units = Units.KOPECKS): BigDecimal {
        if (raw.isNaN() || raw.isInfinite() || raw <= 0.0) return BigDecimal.ZERO.setScale(2)
        val v = BigDecimal.valueOf(raw)
        val rub = if (shouldDivide(raw, units)) v.movePointLeft(2) else v
        return rub.setScale(2, RoundingMode.HALF_UP)
    }

    private fun shouldDivide(raw: Double, units: Units): Boolean {
        if (units.name == "KOPECKS") return true
        if (units.name == "RUBLES") return false
        return raw >= 1000.0 && raw == Math.floor(raw)   // AUTO
    }
}
