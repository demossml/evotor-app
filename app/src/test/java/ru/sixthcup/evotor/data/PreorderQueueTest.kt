package ru.sixthcup.evotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sixthcup.evotor.net.OrderApi

/** Очередь предзаказов: статусы, кнопки, ETA и тексты ошибок — по таблицам промпта. */
class PreorderQueueTest {

    private fun row(
        id: String = "o1",
        code: String = "123456",
        status: String = "accepted",
        created: String = "2026-11-07T10:00:00.000Z",
        items: Int = 2,
        eta: String? = null,
        now: String? = null,
    ) = OrderApi.ActiveRow(
        id = id, shortCode = code, status = status, createdAt = created,
        estimateTotal = 650, itemsCount = items, etaReadyAt = eta, serverNow = now,
    )

    @Test
    fun `status labels cover the whole chain`() {
        assertEquals("Новый", PreorderQueue.statusLabel("draft"))
        assertEquals("Принят", PreorderQueue.statusLabel("accepted"))
        assertEquals("Готовится", PreorderQueue.statusLabel("cooking"))
        assertEquals("Готов", PreorderQueue.statusLabel("ready"))
        assertEquals("Оплачен", PreorderQueue.statusLabel("paid"))
        assertEquals("—", PreorderQueue.statusLabel(null))
    }

    @Test
    fun `buttons follow the status matrix`() {
        assertEquals(
            listOf(PreorderQueue.Action.ACCEPT, PreorderQueue.Action.REJECT),
            PreorderQueue.actions("draft"),
        )
        assertEquals(
            listOf(PreorderQueue.Action.COOKING, PreorderQueue.Action.READY, PreorderQueue.Action.PULL, PreorderQueue.Action.REJECT),
            PreorderQueue.actions("accepted"),
        )
        assertEquals(
            listOf(PreorderQueue.Action.READY, PreorderQueue.Action.PULL, PreorderQueue.Action.REJECT),
            PreorderQueue.actions("cooking"),
        )
        assertEquals(listOf(PreorderQueue.Action.PULL, PreorderQueue.Action.REJECT), PreorderQueue.actions("ready"))
        assertEquals(listOf(PreorderQueue.Action.CLOSE), PreorderQueue.actions("paid"))
        assertTrue(PreorderQueue.canPull("ready"))
        assertFalse("новый заказ нельзя тянуть в чек", PreorderQueue.canPull("draft"))
        assertFalse(PreorderQueue.canPull("paid"))
    }

    @Test
    fun `transitions go strictly step by step`() {
        assertEquals("accepted", PreorderQueue.nextStatus("draft"))
        assertEquals("cooking", PreorderQueue.nextStatus("accepted"))
        assertEquals("ready", PreorderQueue.nextStatus("cooking"))
        assertNull(PreorderQueue.nextStatus("ready"))
        assertNull(PreorderQueue.nextStatus("paid"))
    }

    @Test
    fun `eta counts from server time, not local clock`() {
        val eta = PreorderQueue.etaMinutes(
            "2026-11-07T10:07:00.000Z",
            "2026-11-07T10:01:00.000Z",
            nowMs = 1_000_000L,
        )
        assertEquals(6, eta)
        assertEquals("~6 мин", PreorderQueue.etaText("accepted", "2026-11-07T10:07:00.000Z", "2026-11-07T10:01:00.000Z", 1L))
    }

    @Test
    fun `eta without date means we are still estimating`() {
        assertNull(PreorderQueue.etaMinutes(null, "2026-11-07T10:01:00.000Z"))
        assertEquals("уточняем", PreorderQueue.etaText("cooking", null, null))
        assertEquals("уточняем", PreorderQueue.etaText("accepted", "2026-11-07T09:00:00.000Z", "2026-11-07T10:01:00.000Z"))
    }

    @Test
    fun `ready wins over eta`() {
        assertEquals("Готов", PreorderQueue.etaText("ready", "2026-11-07T10:30:00.000Z", "2026-11-07T10:01:00.000Z"))
    }

    @Test
    fun `banner is empty without orders and names the first one`() {
        assertNull(PreorderQueue.bannerText(emptyList()))
        val one = PreorderQueue.bannerText(listOf(row(items = 2)))
        assertTrue(one!!, one.contains("Заказ 123456"))
        assertTrue(one, one.contains("2 поз."))
        val many = PreorderQueue.bannerText(listOf(row(id = "a"), row(id = "b", code = "999"), row(id = "c")))
        assertTrue(many!!, many.contains("ещё 2"))
    }

    @Test
    fun `drafts come first then by creation`() {
        val sorted = PreorderQueue.sortForQueue(
            listOf(
                row(id = "ready", status = "ready", created = "2026-11-07T09:00:00.000Z"),
                row(id = "new2", status = "draft", created = "2026-11-07T11:00:00.000Z"),
                row(id = "cook", status = "cooking", created = "2026-11-07T10:30:00.000Z"),
                row(id = "new1", status = "draft", created = "2026-11-07T10:10:00.000Z"),
            ),
        )
        assertEquals(listOf("new1", "new2", "cook", "ready"), sorted.map { it.id })
    }

    @Test
    fun `error texts follow the matrix`() {
        assertEquals("Терминал не авторизован. Перепривяжите кассу", PreorderQueue.errorText("unauthorized"))
        assertEquals("Заказ оформлен для другой кофейни", PreorderQueue.errorText("wrong_store"))
        assertEquals("Заказ не найден", PreorderQueue.errorText("not_found"))
        assertEquals("Срок заказа истёк", PreorderQueue.errorText("expired"))
        assertEquals("Заказ уже оплачен", PreorderQueue.errorText("already_paid"))
        assertEquals("Заказ отклонён или отменён", PreorderQueue.errorText("rejected"))
        assertEquals("Статус уже изменился — обновите заказ", PreorderQueue.errorText("invalid_transition"))
        assertEquals("Нет связи с сервером", PreorderQueue.errorText("network"))
        // Код неизвестен — падаем на HTTP-код, а не показываем сырое английское слово.
        assertEquals("Терминал не авторизован. Перепривяжите кассу", PreorderQueue.errorText("weird", 401))
        assertEquals("Ошибка сервера (500)", PreorderQueue.errorText("weird", 500))
        assertEquals("Нет связи с сервером", PreorderQueue.errorText(null, 0))
    }

    @Test
    fun `order expiration is computed from server time`() {
        assertEquals(
            20,
            PreorderQueue.expiresInMinutes("2026-11-07T10:21:00.000Z", "2026-11-07T10:01:00.000Z", 0L),
        )
        assertEquals(0, PreorderQueue.expiresInMinutes("2026-11-07T09:00:00.000Z", "2026-11-07T10:01:00.000Z", 0L))
        assertNull(PreorderQueue.expiresInMinutes(null, null, 0L))
    }
}
