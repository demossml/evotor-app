package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.CatalogApi
import java.math.BigDecimal

/**
 * Вертикальный срез на реальном ответе `GET /api/devices/catalog?storeUuid=20261017-AD69-…`
 * (точка «Багратиона»): локальные SKU терминала → merge → toppings → секции sheet → строки чека.
 */
class CappuccinoVerticalSliceTest {

    private val body: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("catalog-prod-bagrationa.json")!!
            .bufferedReader().use { it.readText() }
    }
    private val payload: CatalogApi.Payload by lazy { CatalogApi.parsePayload(body) }

    private val u250 = "eb7ea0bf-86c8-458a-9a3d-b8c5c81e8d2b"
    private val u350 = "538dda8a-371a-409e-8087-7533de763596"
    private val u400 = "92419352-1782-4c33-a1a9-d7fb18b806ba"

    private fun sku(uuid: String, name: String, article: String? = null, description: String? = null) = CatalogProduct(
        uuid = uuid, name = name, priceRub = BigDecimal("200"), allowToSell = true,
        recipe = null, description = description, article = article,
    )

    /** Добавки как их видит терминал: SKU Эвотор с теми же uuid, что в схеме. */
    private fun addonSkus(): List<CatalogProduct> = payload.byUuid.values
        .flatMap { it.toppings }
        .mapNotNull { t -> t.evotorUuid?.let { sku(it, t.name) } }
        .distinctBy { it.uuid }

    private fun card(local: List<CatalogProduct>): Pair<List<CatalogProduct>, SheetContract.Scheme> {
        val merged = CatalogApi.merge(local + addonSkus(), payload)
        val model = DrinkVariants.build(merged)
        val tile = model.tiles.single { it.variantGroup == "admin-group:2" }
        val variants = SheetContract.cardVariants(model.variantsOf(tile), payload)
        return variants to SheetContract.scheme(variants, payload, catalogOk = true)
    }

    private fun assertGreen(variants: List<CatalogProduct>, scheme: SheetContract.Scheme) {
        assertEquals(listOf(250, 350, 400), variants.map { it.sizeMl })
        assertEquals(SheetContract.Scheme.OK, scheme)
        assertTrue("topsPerVariant=${SheetContract.topsPerVariant(variants)}", SheetContract.topsPerVariant(variants).all { it > 0 })
        for (v in variants) {
            val g = SheetContract.group(v.toppings)
            assertEquals("milk ${v.sizeMl}", 5, g.milk.size)
            assertEquals("syrup ${v.sizeMl}", 7, g.syrup.size)
            assertEquals("extra ${v.sizeMl}", 6, g.extra.size)
        }
    }

    @Test
    fun `backend closed - cappuccino carries milk scheme on all three sizes`() {
        val caps = listOf(4, 5, 6).map { pid -> payload.byUuid.values.single { it.productId == pid } }
        assertTrue(caps.all { it.groupId == 2 })
        assertTrue(caps.all { e -> e.toppings.any { it.groupKey == "milk" } })
        assertEquals(listOf("sc-4", "sc-5", "sc-6"), caps.map { it.articleNumber })
    }

    @Test
    fun `point uuid links - three sizes green`() {
        val (variants, scheme) = card(listOf(
            sku(u250, "Капучино 250 мл"), sku(u350, "Капучино 350 мл"), sku(u400, "Капучино 400 мл"),
        ))
        assertGreen(variants, scheme)
    }

    @Test
    fun `foreign uuids and terminal names differ - sc article alone is enough`() {
        val local = listOf(
            sku("t-1", "КАПУЧИНО S", "sc-4"),
            sku("t-2", "Капучино M", "sc-5"),
            sku("t-3", "Капучино L", "sc-6"),
        )
        val (merged, stats) = CatalogApi.mergeWithStats(local + addonSkus(), payload)
        assertEquals(3, stats.byArticle)
        val model = DrinkVariants.build(merged)
        val tile = model.tiles.single { it.variantGroup == "admin-group:2" }
        val variants = SheetContract.cardVariants(model.variantsOf(tile), payload)
        assertEquals(3, variants.size)
        assertEquals(SheetContract.Scheme.OK, SheetContract.scheme(variants, payload, true))
        assertTrue(SheetContract.topsPerVariant(variants).all { it == 18 })
    }

    @Test
    fun `mixed identities still form one card with one scheme`() {
        val (variants, scheme) = card(listOf(
            sku(u250, "Капучино 250 мл"),
            sku("t-350", "Капучино 350 мл (стар.)", "sc-5"),
            sku("t-400", "Капучино 400 мл"),
        ))
        assertGreen(variants, scheme)
    }

    @Test
    fun `card scheme reaches a size whose own merge produced no toppings`() {
        val payloadNoGroupTops = payload
        val s250 = sku(u250, "Капучино 250 мл")
        val merged = CatalogApi.merge(listOf(s250), payloadNoGroupTops).single()
        val stale350 = sku("t-350", "Cap 350").copy(variantGroup = merged.variantGroup, groupId = 2, sizeMl = 350)
        val stale400 = sku("t-400", "Cap 400").copy(variantGroup = merged.variantGroup, groupId = 2, sizeMl = 400)
        val variants = SheetContract.cardVariants(listOf(merged, stale350, stale400), null)
        assertTrue(SheetContract.topsPerVariant(variants).all { it == 18 })
    }

    @Test
    fun `terminal milk choice goes to receipt with its evotor uuid`() {
        val (variants, _) = card(listOf(
            sku(u250, "Капучино 250 мл"), sku(u350, "Капучино 350 мл"), sku(u400, "Капучино 400 мл"),
        ))
        val v350 = variants.single { it.sizeMl == 350 }
        val milk = SheetContract.group(v350.toppings).milk.first { !it.zeroOption && !it.evotorUuid.isNullOrBlank() }
        val lines = listOf(
            CartLine(v350.uuid, v350.name, v350.priceRub),
            CartLine(milk.evotorUuid!!, "${milk.name} (${v350.name})", milk.priceRub, optionId = milk.id),
        )
        assertEquals(u350, lines[0].productUuid)
        assertEquals(milk.evotorUuid, lines[1].productUuid)
        val catalog = CatalogApi.merge(variants + addonSkus(), payload)
        assertTrue(ReceiptValidator.validate(lines, catalog).isEmpty())
    }

    @Test
    fun `http ok but card not matched - mapping error, not silent empty`() {
        val local = listOf(250, 350, 400).map { ml ->
            sku("x-$ml", "Cappuccino classic $ml", description = "#group=cap #size=$ml")
        }
        val merged = CatalogApi.merge(local, payload)
        val variants = SheetContract.cardVariants(merged.map { it.copy(variantGroup = "cap", sizeMl = it.sizeMl ?: 0) }, payload)
        assertTrue(variants.all { it.toppings.isEmpty() })
        assertEquals(SheetContract.Scheme.MAPPING_ERROR, SheetContract.scheme(variants, payload, catalogOk = true))
        assertEquals(SheetContract.Scheme.EMPTY_IN_DATA, SheetContract.scheme(variants, payload, catalogOk = false))
    }

    @Test
    fun `tea without scheme in backend data is legitimately empty`() {
        val tea = listOf("Чай чёрный 300 мл", "Чай чёрный 400 мл").map { n ->
            val e = payload.byNameExact[Names.normalizeExact(n)]!!
            val uuid = payload.byUuid.entries.single { it.value === e }.key
            sku(uuid, n)
        }
        val merged = CatalogApi.merge(tea, payload)
        val model = DrinkVariants.build(merged)
        val variants = SheetContract.cardVariants(model.variantsOf(model.tiles.single()), payload)
        assertEquals(2, variants.size)
        assertEquals(SheetContract.Scheme.EMPTY_IN_DATA, SheetContract.scheme(variants, payload, catalogOk = true))
    }

    @Test
    fun `addon skus stay hidden from menu tiles`() {
        val merged = CatalogApi.merge(
            listOf(sku(u250, "Капучино 250 мл"), sku(u350, "Капучино 350 мл"), sku(u400, "Капучино 400 мл")) + addonSkus(),
            payload,
        )
        val tiles = DrinkVariants.build(merged).tiles
        val addonUuids = addonSkus().map { it.uuid }.toSet()
        assertTrue(tiles.none { it.uuid in addonUuids })
        assertEquals(1, tiles.size)
    }
}
