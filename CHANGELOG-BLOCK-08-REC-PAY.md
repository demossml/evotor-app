# Block 08 — rec sheet + pay/checkout (index → Android)

Источник: index `#recOverlay`, `#payOverlay` (визуал).

## rec-sheet
- Head «Что-то к напитку?» + subtitle
- Список upsell из backend (как раньше)
- Foot: «Пропустить» + «К оплате · sum»

## pay/checkout
- Head «Оплата · Чек · … · Эвотор»
- Guest row: карта + стаканы
- Резерв / gift / cashback — прежняя DiscountCalc логика
- Note: оплату принимает Эвотор
- CTA «Оплатить на Эвоторе» → SellLauncher (не эмулируем card/cash UI из прототипа — это сломало бы фискализацию)

## Не трогали
- SellLauncher / PendingPayment / loyalty resolve
