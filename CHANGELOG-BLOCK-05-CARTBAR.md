# Block 05 — cartbar (index → Android)

Источник: index `.cartbar`, `.cart-count`, `.cart-sum`, `.cart-cta`, `.empty-cartbar`.

## Что сделано
- Заполненный чек: тёмная плашка #171410, height 66, radius 20.
  - count circle 31 (фон #F6F3EE)
  - «Чек · с собой» + сумма 17px
  - CTA brand «К оплате →» height 48 radius 14
- Тап count/sum → showCart(); CTA → showUpsellThenCheckout (существующий pay-flow).
- Пустой чек: dashed empty-cartbar «Чек пуст / Начните с хитов».
- Кнопку «Повторить» **не** добавляли — в APK нет прошлого заказа (не выдумываем).
- wideLayout sidebar «Изменить заказ» без изменений.

## Не трогали
- cart sheet / item sheet / rec / pay overlays (блок 06+)
- Evotor / API
