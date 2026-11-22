# Очередь бариста — касса APK (серия STEP-0…STEP-6)

**Ветка:** `main` · **База:** `c488dea` (docs: design handoff)

```
versionCode/Name: 178 / 5.3.19-kitchen-queue
When create is called: after (SELL закрыт в POS = оплата подтверждена)
Idempotent clientOrderId: yes
Items mapping: yes
Sell not blocked on kitchen fail: yes
Tests: 683/0
APK path: dist/sixthcup-evotor-5.3.19-kitchen-queue-vc178.apk
```

## Что сделано по шагам

### STEP-0 · Baseline
- `versionCode 177 / 5.3.18-phone-loyalty-fix`, HEAD `c488dea`.
- Найдены точки: `MainActivity.sendReceipt()` → `SellLauncher.openSellReceipt()` → `markPendingOrder()`
  → `PendingPayment.set()` → `onResume()` (SELL закрыт) → `confirmPendingOrder()`.
- `QueueNumberApi` уже выдаёт номер на чек, `DeviceHeaders` даёт store/device, `OrderQueueStore` — локальный PENDING.
- Базовая линия тестов: **657 тестов, 0 падений** (82 suite).

### STEP-1 · `KitchenApi`
`app/src/main/java/ru/sixthcup/evotor/net/KitchenApi.kt`
- `create(clientOrderId, storeUuid, lines, totalKopecks, guestUserId, cardCodeMask, queueOrderId)` —
  `POST /api/devices/kitchen/orders?storeUuid=…`.
- `statuses(ids, storeUuid)` — `GET /api/devices/kitchen/orders?ids=…&storeUuid=…`.
- `body()` и `parseCreated()` / `parseStatuses()` вынесены отдельно (тестируются без сети).
- Классификация ошибок: `409 → already_exists` (не повторяем), 401/403/404 — финальные,
  429/5xx/сеть — retryable. Нет `storeUuid` — запрос не отправляется.
- `isDone()` понимает `done/ready/completed/finished` в любом регистре.

### STEP-2 · Корзина → позиции бариста
`app/src/main/java/ru/sixthcup/evotor/data/KitchenItems.kt` — чистая функция `fromCart(List<CartLine>)`:
- одна строка товара = одна позиция заказа (`name`, `qty`);
- `mods` — из `recipe` основной строки (объём, молоко, сироп, сахар, добавки, выбор) + `choiceLabel`;
- объём/вкус, который уже есть в названии товара («Капучино 350 мл»), не дублируется;
- дубли модификаторов схлопываются, первые буквы капитализируются;
- строки-опции (`optionId != null`: платное молоко/сироп/добавка) **не** идут отдельными «товарами меню»;
- если после фильтра не осталось ни одной основной строки — показываем строки-опции как позиции
  (заказ не должен стать пустым);
- синтетические `mod:` позиции исключаются (`Cart.isSellable`).

### STEP-3 · `clientOrderId`
`app/src/main/java/ru/sixthcup/evotor/data/KitchenCheckout.kt`:
- id привязывается к PENDING-заказу (`OrderQueueStore.addPending()` в `markPendingOrder()`);
- повторная отправка в рамках одной попытки — **тот же** id;
- новая попытка (новый PENDING, в т.ч. после `Cart.clear()`) — **новый** UUID;
- состояние переживает перезапуск процесса (SharedPreferences), `attach()` в `SixthCupApp`.

### STEP-4 · Вызов create
`MainActivity.createKitchenOrderAsync()` — вызывается из `onResume()`, ветка «SELL закрыт»:
- **после** подтверждённой оплаты (состав зафиксирован, продажа состоялась);
- фоновый поток: UI и возврат в Эвотор не блокируются;
- одна повторная попытка при retryable-ошибке (`KitchenQueue.createWithRetry`);
- при провале — `Log.w` + toast «Очередь не создана — повторите заказ вручную»; чек и продажа не затрагиваются;
- предзаказ, подтянутый в чек (`pulledInCart`), kitchen-заказ не создаёт — он уже в очереди backend;
- номер показывается крупно на post-pay («Заказ № 7») и статус «Готовится у бариста / Готово — можно забирать».

### STEP-5 · Статусы
- `KitchenQueue` (в памяти): `lastQueueNumber`, `lastStatus`, `orderId`.
- `MainActivity.refreshKitchenStatus()` — раз в **20 с** (`KITCHEN_POLL_MS`), старт в `onResume`,
  стоп в `onPause`; один запрос в полёте (`kitchenStatusInFlight`).
- Статус от бариста показан на экране post-pay и в «Очереди» («Заказ № 7 · в работе у бариста / готов»).
- Ошибка опроса игнорируется: продажа и локальная очередь не меняются.

### STEP-6 · Guard
- `./gradlew :app:testDebugUnitTest` — **683 теста, 0 падений** (было 657; +26 новых).
- `./gradlew :app:assembleRelease` — успешно.
- versionCode 178, versionName `5.3.19-kitchen-queue`.
- APK: `dist/sixthcup-evotor-5.3.19-kitchen-queue-vc178.apk` (5.9 МБ).
- Документация: `BACKEND-API.md` (раздел H), `docs/CASHIER-APP.md` (шаг 7).

## Новые тесты (+26)
| Файл | Что проверяет |
|---|---|
| `net/KitchenApiTest.kt` | тело запроса (обязательные/опциональные поля, note, qty ≥ 1, пустые mods), разбор create-ответа, разбор статусов (массив / `orders` / `data` / `order` / одиночный объект / битый JSON), нормализация `isDone` |
| `data/KitchenItemsTest.kt` | 1 напиток + 2 мода → 1 позиция; объём не дублируется; строки-опций не отдельные товары; qty ≥ 1; `choiceLabel` → mod; схлопывание дублей; корзина только из опций; пустая корзина |
| `data/KitchenCheckoutTest.kt` | новый id на первой попытке; тот же id на retry; новый id для новой попытки; замена пустого сохранённого id |
| `data/KitchenQueueRetryTest.kt` | успех не повторяется; retryable → ровно 2 вызова; retry → успех; 4xx не повторяется; статус применяется только к текущему заказу |

## Анти-регресс
- `PhoneUtil`/маска телефона, bonus spend, `canRedeem`, configuration sheet, scanner, `FiscalDiscount` — не тронуты.
- `extras.sc`, `evotorUuid`, `OpenSellReceiptCommand`, `ReceiptValidator`, `FreeCupRules`,
  `queueShowPending`, `PENDING → CONFIRMED → READY` — без изменений.
- Экран оплаты по-прежнему у Эвотора: APK не рисует оплату и сдачу.
- Локальная очередь и номера работают как раньше, даже если kitchen API недоступен.

## WHAT I DID NOT DO
1. **Не звал backend на этапе «перед оплатой».** Точка выбрана «после SELL закрыт»: нет фантомных
   заказов при отмене чека, состав фиксирован. В промпте это допустимый вариант («после sell result success»).
2. **Не делал push/websocket.** Только poll раз в 20 с + перерисовка на resume — как «минимум» из STEP-5.
3. **Не менял локальные статусы очереди по статусу кухни.** `done` от бариста — индикатор, а не
   автоперевод `CONFIRMED → READY` (это меняло бы существующую механику).
4. **Не отправляю `guestUserId` и `cardCodeMask`.** В APK нет этих значений в чистом виде
   (карта маскируется только в UI; `guestUserId` backend отдаёт не всегда) — поля в контракте есть,
   но передаются `null`. Если backend требует — это отдельная микрозадача.
5. **Не добавлял `note` (свободный комментарий).** В APK у строки корзины нет поля комментария;
   схема `note` поддержана в API и тестах, но всегда `null` до появления UI комментария.
6. **Не трогал UI-дизайн.** Экран бариста/PWA — отдельная серия; здесь только данные и номер на кассе.
