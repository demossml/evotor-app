package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class PricesTest {
    private fun d(s: String) = BigDecimal(s)

    @Test fun kopecksDefault() {
        assertEquals(d("150.00"), Prices.priceFromEvotorRaw(15000.0))
        assertEquals(d("1.50"), Prices.priceFromEvotorRaw(150.0))
        assertEquals(d("150.50"), Prices.priceFromEvotorRaw(15050.0))
        assertEquals(d("0.00"), Prices.priceFromEvotorRaw(0.0))
    }

    @Test fun autoHeuristicDoesNotDivideTwice() {
        assertEquals(d("150.00"), Prices.priceFromEvotorRaw(15000.0, Prices.Units.AUTO))
        assertEquals(d("150.00"), Prices.priceFromEvotorRaw(150.0, Prices.Units.AUTO))
        assertEquals(d("0.00"), Prices.priceFromEvotorRaw(0.0, Prices.Units.AUTO))
    }

    @Test fun rublesMode() {
        assertEquals(d("150.00"), Prices.priceFromEvotorRaw(150.0, Prices.Units.RUBLES))
    }

    @Test fun parseUnits() {
        assertEquals(Prices.Units.KOPECKS, Prices.Units.parse(null))
        assertEquals(Prices.Units.AUTO, Prices.Units.parse(" auto "))
        assertEquals(Prices.Units.KOPECKS, Prices.Units.parse("junk"))
    }
}
