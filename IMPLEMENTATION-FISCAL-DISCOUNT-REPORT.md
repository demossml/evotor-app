# IMPLEMENTATION — скидка в фискальном чеке + подарок 1 ₽

Промпт: `PROMPTS-CASHIER-FISCAL-DISCOUNT-1RUB-SEQUENTIAL.md` (F00–F07) · 28.10.2026
Сборка: **5.2.6-fiscal1 / versionCode 131** · тесты **245 / 0**

## 1. Диагноз (F00)

Скидка считалась правильно, но **обнулялась на выходе**: сумма чека бралась только из
`ReceiptApi.getReceipt(...)`, и любая ошибка чтения превращалась в `emptyList()` без лога.
Тогда `gross = 0`, а итог скидки считался как `min(free + cb, gross)` = 0 — в ККТ уходила
полная сумма. Ошибка была невидимой: `catch { emptyList() }` и ни одной записи в logcat.

Проверено по API `integration-library-0.6.27`: первый аргумент `ReceiptDiscountEventResult` —
это именно скидка (`getDiscount()`, рубли), action в манифесте совпадает с `NAME_SELL_RECEIPT`,
`CheckoutChoice` к моменту события не очищается (чистится только в `onResume` после возврата
с экрана оплаты).

## 2. Что сделано

| Шаг | Изменение |
|---|---|
| F01 | `DiscountCalc.GIFT_FISCAL_MIN_KOPECKS = 100`; `DiscountCalc.breakdown(...) → PayBreakdown(grossKopecks, discountKopecks, netKopecks, giftApplied, voucherId, floorApplied)` |
| F02 | `DiscountIntegrationService` считает через `breakdown`; `gross` = позиции чека, при недоступности — корзина APK; подписи в лог `fiscal_discount …` и `receipt_positions_unavailable` |
| F03 | `SellLauncher` не ставит 0 ₽ — путь **integration-only** (fallback «цена линии 1 ₽» не включал, чтобы не удвоить скидку) |
| F04 | «К оплате» и «скидка» в UI берутся из того же breakdown; подпись «Подарок −249 ₽ (в чеке 1 ₽)» |
| F05 | Смешанный чек и ваучер 100% покрыты тестами |
| F06 | `extras.sc` не менялся: `free`/`cb`/`voucherId` только с `op` |
| F07 | 131 / `5.2.6-fiscal1`, APK собран |

## 3. Правила пола 1 ₽

| Сценарий | Скидка | К оплате |
|---|---|---|
| Кофе 250 ₽ подарком | 249 ₽ | **1 ₽** |
| Подарок 180 ₽ + платный 120 ₽ | 180 ₽ | 120 ₽ |
| Ваучер 15% на 400 ₽ | 60 ₽ | 340 ₽ |
| Ваучер 100% на 300 ₽ | 299 ₽ | **1 ₽** |
| Чек 0.50 ₽ с подарком | 0 ₽ | 0.50 ₽ (не раздуваем) |
| Без льготы | 0 ₽ | полная сумма |

## 4. Self-check

```
[x] UI net == PayBreakdown.net
[x] Integration discount == PayBreakdown.discount
[x] Gift never 0 kopecks on fiscal path
[x] Gift coffee → expected net 1 RUB in unit test
[x] No gift → full sum
[x] Scanner / L-00 untouched
```

## 5. Проверки

- `:app:testDebugUnitTest` — 245 / 0 (было 238, +7 тестов breakdown).
- `:app:lintDebug` — BUILD SUCCESSFUL, 0 ошибок.
- `:app:assembleRelease` — BUILD SUCCESSFUL.
- APK: `app/build/outputs/apk/release/app-release.apk`, 5 713 160 байт,
  `versionCode=131`, `versionName=5.2.6-fiscal1`, подпись `CN=6.7 Coffee`.

## 6. Что проверить на терминале (обязательно)

1. Продажа с подарком → на бумажном чеке скидка = цена напитка − 1 ₽, итог **1 ₽**.
2. `logcat -s DiscountIntegration`: строка `fiscal_discount gross=… discount=… net=… floor=true`.
3. Если `discount=0` при `receiptPositions>0` и живом резерве — значит событие
   `receiptDiscount` до сервиса не доходит (прошивка/разрешение). Тогда единственный
   безопасный fallback — цена подарочной линии 1 ₽ в `SellLauncher` (сейчас **не** включён,
   чтобы не удвоить скидку). Логи дадут точный ответ.
4. `receipt_positions_unavailable fallback=cart` — подтверждение, что старый код в этой ситуации
   отдавал 0 скидки.

## 7. Не проверено

Терминал: фактическая печать чека, поведение `ReceiptApi` в сервисном процессе, реакция ККТ
на итог 1 ₽. Всё остальное покрыто unit-тестами.
