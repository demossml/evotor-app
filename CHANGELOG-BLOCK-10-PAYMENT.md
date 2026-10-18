# Block 10 — Screen.PAYMENT (index processing visual)

## Что сделано
- Head «Оплата · Эвотор» / «Чек · …»
- Центральная панель: ring, «Чек открыт на терминале», сумма, гость
- Список позиций компактно
- CTA: «Чек пробит — новый заказ» → clear CardSession+Cart+PendingPayment → CARD
- «Не оплачено — к заказу» → clear PendingPayment → ORDER

## Не трогали
- SellLauncher / PendingPayment TTL / lastCardCode memory
