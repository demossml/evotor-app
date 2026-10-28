# STEP-FISCAL-DISCOUNT-PROGRESS

Промпт: `PROMPTS-CASHIER-FISCAL-DISCOUNT-1RUB-SEQUENTIAL.md` · F00 → F07

## F00 — Baseline и диагноз

| | |
|---|---|
| HEAD на старте | `99cd5ff` (5.2.5-discount-scroll, 130) |
| Тесты на старте | 238 / 0 |

Что прочитано: `DiscountCalc`, `CheckoutChoice`, `Voucher/VoucherCalc`, `DiscountIntegrationService`,
`SellLauncher`, `Cart` (+`Cart.attach` в `SixthCupApp`), `MainActivity.onResume` (сброс выбора после чека).

Проверено по API библиотеки (`javap` на `integration-library-0.6.27`):

- `ReceiptDiscountEventResult(java.math.BigDecimal discount, SetExtra, List<IPositionChange>, SetPurchaserContactData)`
  — первый аргумент **и есть скидка** (`getDiscount()`), размерность — рубли (`BigDecimal`);
- `ReceiptDiscountEvent.NAME_SELL_RECEIPT = "evo.v2.receipt.sell.receiptDiscount"` — action в манифесте совпадает;
- `CheckoutChoice.clear()` не вызывается ни при открытии чека, ни в сервисе скидки — только после
  возврата с экрана оплаты (`onResume`) и на ошибках открытия. Значит выбор жив к моменту события.

### Главная причина расхождения UI и чека

Сервис брал сумму чека **только** из `ReceiptApi.getReceipt(...)`:

```kotlin
val prices = receiptUnitPrices()            // при любой ошибке → emptyList(), без лога
val gross = prices.fold(ZERO) { … }         // = 0
val total = free + cb                       // .min(gross) → 0
```

При недоступных позициях (сервис поднят в отдельном процессе, чек ещё не сформирован, исключение
в `ReceiptApi`) `gross` становился нулём, и **любая скидка занулялась через `min(gross)`** —
в ККТ уходила полная сумма. Ошибка чтения при этом молча глоталась (`catch { emptyList() }`),
поэтому в логах не было ни следа.

## F01 — PayBreakdown с полом 1 ₽

- `DiscountCalc.GIFT_FISCAL_MIN_KOPECKS = 100`.
- `DiscountCalc.breakdown(gross, eligible, card, choice, nowSec): PayBreakdown`
  (`grossKopecks`, `discountKopecks`, `netKopecks`, `giftApplied`, `voucherId`, `floorApplied`).
- Правила: подарок/ваучер и `net < 100` → `net = 100`, `discount = gross − 100`;
  чек дешевле 1 ₽ не раздуваем (скидки нет); без карты — полная сумма.
- Тесты: кофе 250 ₽ подарком → discount 249, net 1; без льготы → 0 / gross; ваучер 15% → 60/340;
  ваучер 100% → floor; чек 0.50 ₽ → без скидки.

## F02 — Сервис на том же breakdown

- `gross` = позиции чека, а при пустом/нечитаемом чеке — `Cart.gross()` (+ предупреждение в лог).
- Подходящие единицы: из `Cart.giftEligibleLines()`; если корзина пуста, а позиции есть — самая
  дешёвая позиция чека (иначе подарок обнулялся бы).
- Скидка = `breakdown.discountKopecks + combo`, с повторной проверкой пола на итоге.
- Лог: `fiscal_discount gross=… discount=… net=… gift=… voucher=… floor=… receiptPositions=…`.

## F03 — SellLauncher

Нулевых цен нет: позиция уходит с ценой терминала (`terminalPrice ?: line.priceRub`), скидку
применяет только `ReceiptDiscountEvent`. **Выбран путь integration-only** — он предпочтителен по
промпту; fallback «цена линии 1 ₽» не включён, чтобы не получить двойную скидку.

## F04 — UI

- «К оплате» = `breakdown.netKopecks` (через `LoyaltyPreview`), «скидка» = `discountKopecks`.
- Подпись «Подарок −249 ₽ (в чеке 1 ₽)», когда сработал пол.
- Скролл и липкая кнопка из 5.2.5 не тронуты.

## F05 — Смешанный чек

- Подарок 180 ₽ + платный 120 ₽ → discount 180, net 120 (пол не нужен).
- Ваучер 100% на 300 ₽ → discount 299, net 1.
- Тесты в `DiscountCalcTest`.

## F06 — extras.sc

Не менялись: `free`/`cb`/`voucherId` пишутся только с `op` и резервом (тесты `CardSessionExtrasTest`).
Продажа с `net = 1 ₽` — обычный SELL, чашка считается по `doc_id` на backend.

## F07 — Версия

131 / `5.2.6-fiscal1`, тесты 245 / 0, lint без ошибок, release APK собран.
