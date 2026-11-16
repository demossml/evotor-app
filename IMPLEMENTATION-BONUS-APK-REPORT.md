## IMPLEMENTATION-BONUS-APK-REPORT

versionCode/Name: `171` / `5.3.12-bonus-phone-cashback`

CustomersApi: yes

Phone UI: yes

Spend 100%: yes

Gift sixth disabled when no free: yes

Tests: 634/0

APK path: `app/build/outputs/apk/release/app-release.apk`

WHAT I DID NOT DO: backend, SMS, change % by cashier

---

## Что сделано

### 1. Клиент phone-bonus

- Добавлен `net/CustomersApi.kt`:
  - `POST /api/devices/customers/lookup` с `{phone}`;
  - `POST /api/devices/customers/register` с `{phone,pdConsent:true,pdConsentVersion:"v1"}`;
  - те же `DeviceHeaders`, что у `LoyaltyApi`;
  - разбор `found`, `cardCode`, `cardId`, `balanceKopecks`, `effectiveEarnPercent`, `earnSource`, `phoneMasked`, `created`;
  - человекочитаемые ошибки: invalid phone, 429, 503, сеть, timeout.
- Добавлен `data/PhoneUtil.kt`: нормализация до `7XXXXXXXXXX`, валидация, ввод-маска, маска отображения.
- На сервер уходят только цифры; в UI и кэш попадает только маска.

### 2. Модель карты

- `CardInfo` расширен:
  - `balanceKopecks`;
  - `effectiveEarnPercent`;
  - `phoneMasked`;
  - `identifiedBy = PHONE | QR | CODE`;
  - `canRedeem`.
- `CardSession` сохраняет/читает новые поля и поддерживает старый кэш.
- `CardSession.cashbackRedeemable()`:
  - QR/CODE — нужен живой reservation + `canRedeem`;
  - PHONE — достаточно серверного `canRedeem`, без reservationId.
- `CardSession.sc`:
  - для phone пишет `cb`, но не пишет `free` и не открывает sixth-cup gift;
  - `free` по-прежнему только при reservation + canRedeem.
- `CardRefresh` сохраняет телефонные метаданные при повторном resolve по cardCode.

### 3. UI

- В гостевом экране добавлена точка входа «Телефон / карта кэшбэка».
- Шторка:
  - поле телефона с маской;
  - «Найти» → lookup;
  - найден: маска, баланс, effective % (read-only), «К заказу» / «Списать бонусы»;
  - не найден: короткий текст согласия v1 + checkbox + «Выпустить карту»;
  - register не вызывается без галочки;
  - ошибки показываются кассиру без стеков и внутренних кодов.
- Перед привязкой выполняется `resolve`; при его ошибке карта остаётся с `canRedeem=false` и списание недоступно.
- Шторка списания:
  - `maxSpendKopecks = min(balance, cartTotal)`;
  - по умолчанию максимум;
  - SeekBar позволяет уменьшить сумму;
  - 100% корзины → в чеке остаётся фискальный минимум 1 ₽;
  - сохраняется через `CheckoutChoice`.
- После успешной продажи:
  - баланс обновляется через resolve/lookup;
  - текст «Бонусы за покупку появятся в течение нескольких минут»;
  - spend choice очищается.

### 4. Фискальная логика

- `DiscountCalc` / `FiscalCalc` / `LoyaltyBenefit` получили `cashbackAllowed`:
  - phone-bonus не требует reservation;
  - QR/CODE по-прежнему требует reservation;
  - `canRedeem=false` → списания нет.
- `FiscalCalc.split`:
  - cashback может покрыть 100% корзины;
  - скидка на чек ограничена `gross − 1 ₽`;
  - нулевой чек не создаётся;
  - подарок/ваучер и кэшбэк вместе не могут пробить пол 1 ₽.
- `SellLauncher` и `DiscountIntegrationService`:
  - в `sc.cb` уходит фактическая скидка после пола, а не запрошенная сумма;
  - `extrasCb` не может превысить реальную скидку в чеке;
  - `sc.free` не выставляется для phone-bonus.

### 5. Тесты

Добавлены/обновлены:

- `PhoneUtilTest`;
- `CustomersApiTest`;
- `BonusSpendTest`;
- `CardSessionExtrasTest`;
- `CardProgressTest`;
- `ClientOffersTest`;
- `FiscalDiscountTest`;
- `CardRefreshTest`.

Итог: `634` теста, `0` падений, `0` ошибок.

### 6. Регрессии audit

Проверено, что не откатились:

- `canRedeem` gates;
- `config_unavailable` / `needsConfig`;
- единый `ReservationPolicy` grace;
- `hideFromMenu`;
- debug-панель только в debug.

## Ограничения

- Backend не менялся: клиент реализован по контракту из задания.
- SMS не используется.
- Процент начисления бариста не меняет: только отображается effective % с сервера.
- Если backend ещё не поддерживает `customers/lookup|register` или bonus-режим resolve, UI покажет ошибку сервиса; локальный fallback не подменяет серверные данные.
- Фискальный минимум 1 ₽ сохраняется: при полном списании списывается `gross − 1 ₽`, а не создаётся нулевой чек.
