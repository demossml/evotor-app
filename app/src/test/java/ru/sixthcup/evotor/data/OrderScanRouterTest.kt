package ru.sixthcup.evotor.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скан: заказ гостя (`ord1.`) уходит на свой экран, всё остальное — прежняя loyalty-ветка.
 * Ошибка здесь перехватила бы карту гостя, поэтому проверяем и «похожие» строки.
 */
class OrderScanRouterTest {

    @Test
    fun `order qr is recognised`() {
        assertTrue(OrderScanRouter.isOrderQr("ord1.eyJvaWQiOiJhYmMifQ.sig"))
    }

    @Test
    fun `loyalty tokens are not order qr`() {
        // Формат карты: подписанный токен / цифры / QR с точками — но без префикса ord1.
        assertFalse(OrderScanRouter.isOrderQr("eyJjYXJkIjoiMTIzIn0.c2ln"))
        assertFalse(OrderScanRouter.isOrderQr("1234567890"))
        assertFalse(OrderScanRouter.isOrderQr("6951234567890"))
        assertFalse(OrderScanRouter.isOrderQr("order1.abc.def"))
        assertFalse(OrderScanRouter.isOrderQr("ord1.onlyonepart"))
    }

    @Test
    fun `blank input is safe`() {
        assertFalse(OrderScanRouter.isOrderQr(""))
        assertFalse(OrderScanRouter.isOrderQr("   "))
        assertNull(OrderScanRouter.extractOid(""))
    }

    @Test
    fun `extract oid refuses non order payloads`() {
        assertNull(OrderScanRouter.extractOid("1234567890"))
        assertNull(OrderScanRouter.extractOid("eyJjYXJkIjoiMTIzIn0.c2ln"))
    }
}
