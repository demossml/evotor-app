# IMPLEMENTATION — скидка в ККТ через `priceWithDiscountPosition`

Промпт: `PROMPTS-CASHIER-POSITION-DISCOUNT-SEQUENTIAL.md` (P00–P07) · 28.10.2026
Сборка: **5.2.8-position-discount / versionCode 133** · тесты **263 / 0**

## 1. Почему сменили путь

По `RESEARCH-EVOTOR-FISCAL-DISCOUNT.md`: `evo.v2.receipt.sell.receiptDiscount` при
`OpenSellReceiptCommand` **не гарантирован** — на части прошивок скидка применяется только после
тапа по иконке на экране оплаты. Поэтому льгота теперь едет **в цене позиции**
(`Position.Builder.setPriceWithDiscountPosition`), а `ReceiptDiscountEvent` остаётся только для
кэшбэка и комбо.

API проверен по факту в `integration-library-0.6.27` (`javap`): метод существует,
принимает `BigDecimal`, `Position.getPriceWithDiscountPosition()` его читает.

## 2. Расклад скидки

| Куда | Что | Файл |
|---|---|---|
| Позиции (`priceWithDiscountPosition`) | подарок (одна единица → ровно 1 ₽) + ваучер | `FiscalCalc.split` → `FiscalLines.build` → `SellLauncher` |
| Чек (`ReceiptDiscountEventResult`) | кэшбэк + комбо | `DiscountIntegrationService` |

**Удвоения нет:** сервис считает `reason=already_on_positions` и возвращает только чековую часть.

Правила:
- подарок — самая дешёвая **подходящая** единица (`freeEligible`, не добавка), её цена = `1.00 ₽`;
- несколько единиц одного напитка: скидка только на одну, вторая по полной;
- ваучер распределяется по позициям до нуля, но итог чека держит минимум 1 ₽;
- ручной код без резерва и офлайн — цены не меняются.

## 3. Примеры (покрыты тестами)

| Корзина | Льгота | Позиции | К оплате |
|---|---|---|---|
| Латте 250 ₽ | подарок | 1.00 + 0 | **1 ₽** |
| Латте 180 + чизкейк 120 | подарок | 1.00 + 120 | 121 ₽ |
| 2 × латте 250 | подарок | 1.00 + 250 | 251 ₽ |
| Латте 250 + чизкейк 150 | ваучер 15% | −60 ₽ | 340 ₽ |
| Латте 300 | ваучер 100% | 1.00 | **1 ₽** |
| Латте 250 | без льготы | 250 | 250 ₽ |

## 4. Self-check

```
[x] SellLauncher calls setPriceWithDiscountPosition when benefit on
[x] Gift line fiscal >= 1.00 RUB, never 0 (unit price 1.00, тест)
[x] UI total == sum of discounted line totals (один FiscalCalc.split)
[x] Integration does not double-discount (reason=already_on_positions)
[x] Report cites research path (position, not auto receiptDiscount)
```

## 5. Проверки

- `:app:testDebugUnitTest` — 263 / 0 (было 250, +13)
- `:app:lintDebug` — BUILD SUCCESSFUL, 0 ошибок
- `:app:assembleRelease` — BUILD SUCCESSFUL
- APK: `app/build/outputs/apk/release/app-release.apk`, 5 717 660 байт,
  `versionCode=133`, `versionName=5.2.8-position-discount`, подпись `CN=6.7 Coffee`

## 6. Чеклист для человека на терминале (из research §7)

1. Продажа без льготы → цены и итог как раньше.
2. QR + подарок → в чеке одна позиция по **1.00 ₽**, остальные по полной, итог = 1 ₽ + платные.
3. QR + ваучер 15% → на бумаге позиции со скидкой, сумма совпадает с экраном.
4. `logcat -s SellLauncher` → `fiscal_positions discount=… gift=true` и `fiscal_pos … withDiscount=1.00`.
5. `logcat -s DiscountIntegration` → `reason=already_on_positions`, кэшбэк в `discount=` только если его списывали.
6. Иконка скидки на экране оплаты Эвотора **не нужна** — проверить, что без тапа сумма уже со скидкой.
7. Отмена чека / возврат — проверить, что скидка не «залипает» на следующем чеке.

## 7. Не проверено

Терминал: фактическое применение `priceWithDiscountPosition` на прошивке (в research это
подтверждено на другой сборке), поведение ККТ при итоге 1 ₽, отсутствие дубля с иконкой скидки.
Всё денежное — в unit-тестах (13 новых).
