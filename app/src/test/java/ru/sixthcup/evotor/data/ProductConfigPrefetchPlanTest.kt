package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductConfigPrefetchPlanTest {

    @Test
    fun `dedupes and keeps order`() {
        val ids = ProductConfigPrefetchPlan.ids(listOf(3, 1, 3, 2, 1)) { false }
        assertEquals(listOf(3, 1, 2), ids)
    }

    @Test
    fun `skips non positive ids`() {
        val ids = ProductConfigPrefetchPlan.ids(listOf(0, -5, 7)) { false }
        assertEquals(listOf(7), ids)
    }

    @Test
    fun `skips already cached`() {
        val cached = setOf(2, 4)
        val ids = ProductConfigPrefetchPlan.ids(listOf(1, 2, 3, 4, 5)) { it in cached }
        assertEquals(listOf(1, 3, 5), ids)
    }

    @Test
    fun `respects limit`() {
        val ids = ProductConfigPrefetchPlan.ids((1..10).toList(), limit = 3) { false }
        assertEquals(listOf(1, 2, 3), ids)
    }

    @Test
    fun `non positive limit gives empty plan`() {
        assertEquals(emptyList<Int>(), ProductConfigPrefetchPlan.ids(listOf(1, 2, 3), limit = 0) { false })
    }
}
