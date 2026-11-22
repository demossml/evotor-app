package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.KitchenApi

/** Одна повторная попытка: сеть/5xx повторяем, 4xx — нет, успех не повторяем. */
class KitchenQueueRetryTest {

    private val created = KitchenApi.Created("k-1", "7", "new")

    @Test
    fun `success is not retried`() {
        var calls = 0
        val r = KitchenQueue.attempt { calls++; KitchenApi.Result.Ok(created) }
        assertEquals(1, calls)
        assertTrue(r is KitchenApi.Result.Ok)
    }

    @Test
    fun `retryable error is retried once`() {
        var calls = 0
        val r = KitchenQueue.attempt {
            calls++
            KitchenApi.Result.Err("network", retryable = true)
        }
        assertEquals(2, calls)
        assertTrue(r is KitchenApi.Result.Err)
    }

    @Test
    fun `retryable error then success`() {
        var calls = 0
        val r = KitchenQueue.attempt {
            calls++
            if (calls == 1) KitchenApi.Result.Err("network", retryable = true)
            else KitchenApi.Result.Ok(created)
        }
        assertEquals(2, calls)
        assertTrue(r is KitchenApi.Result.Ok)
    }

    @Test
    fun `final 4xx is not retried`() {
        var calls = 0
        KitchenQueue.attempt {
            calls++
            KitchenApi.Result.Err("forbidden", 403, retryable = false)
        }
        assertEquals(1, calls)
    }

    @Test
    fun `status update applies only to the current order`() {
        KitchenQueue.clear()
        KitchenQueue.remember(created)
        KitchenQueue.applyStatus(KitchenApi.Status("other", "9", "done"))
        assertEquals("new", KitchenQueue.last()?.status)
        KitchenQueue.applyStatus(KitchenApi.Status("k-1", "7", "done"))
        assertTrue(KitchenQueue.last()!!.done)
        KitchenQueue.clear()
    }
}
