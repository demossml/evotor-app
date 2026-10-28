# STEP-GIFT-VOUCHER-PROGRESS

Промпт: `PROMPTS-CASHIER-GIFT-VOUCHER-SEQUENTIAL.md` · G00 → G08

## G00 — Baseline

| | |
|---|---|
| HEAD на старте | `9bd9fed` (scanner-fix, `ScannedCode` — не откатывался) |
| versionCode / versionName | 128 / `5.2.3-scanner` |
| Тесты на старте | 227 / 0 падений |

Конспект кода:

- `CardSession` / `CardInfo`: `freeAvailable`, `freeStatus` (`RESERVED/ALREADY_RESERVED/NONE/OFFLINE/ERROR`),
  `reservationId` + `reservationExpiresAt`, `vouchers: List<ResolveVoucher>`, `code` (токен или цифры).
- `DiscountCalc`: `reserved()`, `freeApplies()`, `voucherDiscount()`, `compute()` → `Result(free, voucher, cashback, total)`,
  где `total` — **сумма скидки** (её ждёт `ReceiptDiscountEventResult`).
- `Choice.normalize()` — единственное место, где снимаются конфликты выбора.
- `Voucher` / `VoucherCalc`: парсер `vouchers[]`, `discount()` (делегирует в `DiscountCalc`), `allowedTogether()`, `amountLabel()`.
- UI после resolve: `resolve()` → `CardSession.set` → `screen = Screen.ORDER` → `safeRender()`; выбор льготы жил
  **только** в листе «Лояльность» по кнопке «В чек» (`showLoyaltySheet`).
- `DiscountIntegrationService` читает `CheckoutChoice` + `CardSession` и считает тем же `DiscountCalc`.

**Ответ на вопрос G00.3:** явного выбора ваучера сразу после карты не было; выбор льготы был спрятан в листе,
который открывался только по «В чек». Плитки каталога льготу не показывали.

## G01 — Модель выбора

- `Choice.normalize`: ваучер вытесняет и кэшбэк, и подарок → `free=false`, `cashback=false`.
- Тесты: `CheckoutChoiceTest` — «voucher replaces the gift», «gift then voucher leaves the gift unselected».

## G02 — Подсказка сразу после resolve

- `LoyaltyPreview.canOffer(card)` — только для QR с живым резервом и хоть одной льготой;
  офлайн/`ERROR`/числовой код — не предлагаем.
- `maybePromptBenefit()`: bottom sheet «Льгота гостя» — «Без льготы» / «Применить подарок» / список ваучеров.
  Один раз на привязку (`benefitPrompted`, сбрасывается на новом скане).
- Тесты: `LoyaltyBenefitTest` — `canOffer` true/false (offline, ERROR, code, нет льгот).

## G03 — Бейджи в каталоге

- `CatalogCallbacks.benefitBadge` → `ProductVH` показывает «ПОДАРОК» у плиток с `freeEligible` при выбранном подарке.
- Каталог не фильтруется. Для ваучера бейджей нет: в `resolve` у ваучера нет scope (`categoryIds`/`productIds`),
  он действует на весь чек — помечать все плитки бессмысленно (не выдумываем поля).

## G04 — Корзина

- `cartTotalRub()` = чек − комбо − льгота (подпись «К оплате» справочная, деньги принимает POS).
- В корзине блок: «Подарок −180 ₽», «Ваучер «…» −60 ₽», «Кэшбэк −50 ₽», ниже «К оплате N ₽».
- Полоса корзины: подпись «Чек · подарок −180 ₽».

## G05 — Итог перед оплатой

- Лист «Лояльность» стартует с выбора, сделанного в подсказке после карты.
- Строка «Выбрано: … · скидка −N ₽ · к оплате M ₽» пересчитывается при любом переключении.

## G06 — Чек

- UI и `DiscountIntegrationService` считают одним `DiscountCalc` через `LoyaltyPreview` (тесты на равенство чисел).
- Поток фискализации не менялся.

## G07 — Числовой код

- `canOffer` = false, `sc` без `free`/`cb`/`voucherId` без `op`. Тесты в `LoyaltyBenefitTest` и `CardSessionExtrasTest`.

## G08 — Версия и сборка

- versionCode 129, versionName `5.2.4` (от 128 / `5.2.3-scanner`).
- Тесты после работы: см. отчёт.
