package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.CatalogApi
import java.math.BigDecimal

/**
 * Подарок на кассе: приоритет серверных флагов, единый расчёт для предпросмотра и чека,
 * и жёсткие правила записи в `extras.sc`.
 */
class GiftRulesTest {

    private fun product(uuid: String, name: String, eligible: Boolean) = CatalogProduct(
        uuid = uuid,
        name = name,
        priceRub = BigDecimal("200"),
        allowToSell = true,
        recipe = null,
        description = null,
        article = null,
        freeEligible = eligible,
    )

    private fun payload(json: String) = CatalogApi.parsePayload(json)

    @Test
    fun `server freeEligible wins over the name heuristic`() {
        // Эвристика сказала бы «напиток», но сервер прямо запретил подарок.
        val p = product("u-1", "Капучино", eligible = true)
        val merged = CatalogApi.merge(
            listOf(p),
            payload("""{ "products": [ { "id": 1, "name": "Капучино", "evotorUuid": "u-1", "freeEligible": false } ] }"""),
        )
        assertEquals(false, merged.single().freeEligible)
    }

    @Test
    fun `countsAsCup is used when freeEligible is absent`() {
        val p = product("u-2", "Круассан", eligible = false)
        val merged = CatalogApi.merge(
            listOf(p),
            payload("""{ "products": [ { "id": 2, "name": "Круассан", "evotorUuid": "u-2", "countsAsCup": true } ] }"""),
        )
        assertEquals(true, merged.single().freeEligible)
    }

    @Test
    fun `food is never promoted to a gift by countsAsCup`() {
        val p = product("u-3", "Круассан", eligible = false)
        val merged = CatalogApi.merge(
            listOf(p),
            payload(
                """{ "products": [ { "id": 3, "name": "Круассан", "evotorUuid": "u-3", "countsAsCup": true, "isFood": true } ] }""",
            ),
        )
        assertEquals(false, merged.single().freeEligible)
    }

    @Test
    fun `heuristic stays in charge when the server says nothing`() {
        val p = product("u-4", "Латте", eligible = true)
        val merged = CatalogApi.merge(
            listOf(p),
            payload("""{ "products": [ { "id": 4, "name": "Латте", "evotorUuid": "u-4" } ] }"""),
        )
        assertEquals(true, merged.single().freeEligible)
    }

    @Test
    fun `preview price equals the real discount`() {
        val eligible = listOf(BigDecimal("200.00"), BigDecimal("250.00"))
        // Предпросмотр в листе показывает самую дешёвую позицию.
        val preview = DiscountCalc.cheapestEligible(eligible)
        // Реальная скидка считается тем же DiscountCalc.
        val real = DiscountCalc.compute(
            gross = BigDecimal("450.00"),
            eligibleUnitPrices = eligible,
            reserved = true,
            freeChosen = true,
            freeAvailable = 1,
            requestedCashbackKopecks = 0L,
            cardCashbackKopecks = 0L,
        ).free
        assertEquals(preview, real)
    }

    @Test
    fun `no eligible positions means no free discount`() {
        val r = DiscountCalc.compute(BigDecimal("300"), emptyList(), true, true, 1, 0L, 0L)
        assertEquals(BigDecimal.ZERO, r.free)
    }

    @Test
    fun `free is capped by gross and cashback goes on the remainder`() {
        val r = DiscountCalc.compute(BigDecimal("200.00"), listOf(BigDecimal("200.00")), true, true, 1, 5_000L, 5_000L)
        assertEquals(BigDecimal("200.00"), r.free)
        assertEquals(0, r.cashback.compareTo(BigDecimal.ZERO))
        assertEquals(BigDecimal("200.00"), r.total)
    }

    @Test
    fun `manual code never writes free or cashback`() {
        val info = CardInfo(
            code = "0012",
            cardCode = "12",
            paidCups = 1,
            cupsForFree = 5,
            freeAvailable = 1,
            cashbackKopecks = 5_000,
            reservationId = null,
            reservationExpiresAt = 0L,
            freeStatus = "NONE",
        )
        val sc = CardSession.sc(info, freeApplied = true, cashbackKopecks = 5_000)
        assertEquals(0, sc.getInt("free"))
        assertEquals(0L, sc.getLong("cb"))
        assertTrue(!sc.has("op"))
    }

    @Test
    fun `qr with free keeps op, free and ord together`() {
        val info = CardInfo(
            code = "payload.sig",
            cardCode = "12",
            paidCups = 4,
            cupsForFree = 5,
            freeAvailable = 1,
            cashbackKopecks = 0,
            reservationId = "R-1",
            reservationExpiresAt = 0L,
            freeStatus = "RESERVED",
        )
        val sc = CardSession.sc(info, freeApplied = true, cashbackKopecks = 0, queue = QueueNumber("ord-9", "47"))
        assertEquals(1, sc.getInt("free"))
        assertEquals("R-1", sc.getString("op"))
        assertEquals("ord-9", sc.getString("ord"))
        assertEquals("47", sc.getString("qn"))
        assertEquals("payload.sig", sc.getString("c"))
    }

    @Test
    fun `qr without free keeps op and zero free`() {
        val info = CardInfo(
            code = "payload.sig",
            cardCode = "12",
            paidCups = 2,
            cupsForFree = 5,
            freeAvailable = 1,
            cashbackKopecks = 0,
            reservationId = "R-1",
            reservationExpiresAt = 0L,
            freeStatus = "RESERVED",
        )
        val sc = CardSession.sc(info, freeApplied = false, cashbackKopecks = 0)
        assertEquals(0, sc.getInt("free"))
        assertEquals("R-1", sc.getString("op"))
    }

    @Test
    fun `toppings never become the gift even if flagged eligible`() {
        // Позиция-добавка (optionId != null) исключается из подарка при любом флаге.
        val drink = CartLine("u-drink", "Капучино", BigDecimal("200"), freeEligible = true)
        val topping = CartLine(
            "mod:1", "Сироп", BigDecimal("40"), freeEligible = true, cartKey = "mod:1", optionId = 1,
        )
        val lines = listOf(drink, topping)
        val eligible = lines.filter { it.freeEligible && it.optionId == null }
        assertEquals(listOf("u-drink"), eligible.map { it.productUuid })
        // И скидка считается по цене напитка, а не добавки.
        assertEquals(BigDecimal("200"), DiscountCalc.cheapestEligible(eligible.map { it.priceRub }))
    }
}
