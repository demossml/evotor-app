# Очередь предзаказов — отчёт APK

## 163 / 5.3.4-preorder-benefit (контракт v2)

### Версии и артефакт
| | |
|---|---|
| было | `162` / `5.3.3-preorder-queue` |
| стало | **`163` / `5.3.4-preorder-benefit`** |
| commit | `0d6d3f755ab7689baffa9a4cf6d4aace632fb87c` |
| APK | `dist/sixthcup-evotor-5.3.4-preorder-benefit-vc163.apk`, 6 155 260 байт |
| sha256 | `a8d5a7175fa6ce0897dc391bed4ff1f0c0f5a6e0c4ffa7bf20ed0b6f25eb6b5a` |
| подпись | `CN=Android Debug` — release-ключа на машине сборки не было (см. «Известные ограничения») |
| сборка | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` — успешно |

### Что добавлено под контракт v2
| Поле / правило | Реализация |
|---|---|
| `benefit { kind, title, amountRub }` | `OrderApi.Benefit`; панель заказа рисует `Бонус: <title> −<amount> ₽` |
| `benefitTotal` | Fallback суммы, если `amountRub` не пришёл |
| `quoteTotal` | Строка `Итог: <quoteTotal> ₽` + подпись «Итог подтверждает касса»; `null` → оценка без строки бонуса |
| `modifiers[].groupKey` | Разбор + группировка опций в диалоге «В чек» (`молоко`, `сироп`, `добавки`); в чек ключ не уходит |
| `choice` | **Никогда** не становится позицией чека (даже при `sendToReceipt=true` и uuid) |
| `configText` | Выводится как есть, без пересборки строки на клиенте |
| `409 benefit_conflict` | Текст «Бонус гостя уже применён в другом чеке», «В чек» блокируется, заказ перечитывается, после SELL — notice кассиру |
| JSON-`null` в любом поле | Явная проверка: Android-`optString` возвращает `"null"`, теперь такие поля читаются как `null` |

Скидку APK не считает: `DiscountCalc`, ручные скидки и фискальные файлы не тронуты. Связь чека с
предзаказом — `sc.preorder` (см. «Решение по `sc.ord`»); backend применяет `benefit` по резерву заказа.

### Новые / изменённые файлы (163)
| Файл | Что |
|---|---|
| `net/OrderApi.kt` | `Benefit`, `Order.benefit/benefitTotal/quoteTotal`, `Modifier.groupKey`, helper `optNullableString` |
| `data/PreorderQueue.kt` | `Money`, `money()`, `benefitKindLabel`, `totalRub`, текст `benefit_conflict` |
| `data/PreorderPull.kt` | `choice` не попадает в чек, `ReceiptModifier`, `groupedModifiers`, группа опций в notice |
| `ui/orders/OrderDetailActivity.kt` | строка бонуса и итог, блокировка «В чек» по `benefit_conflict` |
| `ui/MainActivity.kt` | notice после SELL при `benefit_conflict` в `mark-paid` |
| `app/build.gradle.kts` | `versionCode 163`, `versionName 5.3.4-preorder-benefit` |
| `test/net/OrderPayloadParseTest.kt` (новый, 3) | разбор v2, отсутствие полей, JSON-`null` |
| `test/data/PreorderBenefitTest.kt` (новый, 6) | gift/voucher/cashback, `benefit=null`, `quoteTotal=null`, текст ошибки |
| `test/data/PreorderPullTest.kt` | +3: `choice` не в чеке, группировка опций, `configText` как есть |

**Не менялись:** лояльность, `ReceiptValidator`, `DiscountCalc`, `FiscalLines`, `ReceiptPlan`, скидки,
ваучеры, сканер QR карты, `ChoicePrintStore`, парсер конфигурации товара.

### Тесты (XML-отчёты)
`./gradlew :app:testDebugUnitTest` → **560 pass / 0 fail / 0 errors**, 66 классов (было 548 / 64).
- `PreorderBenefitTest` 6 — подписи и суммы `gift|voucher|cashback`, `benefit=null` без строки,
  `quoteTotal=null` → оценка, `benefit_conflict` текст;
- `OrderPayloadParseTest` 3 — контракт v2, отсутствующие поля, явный JSON-`null`;
- `PreorderPullTest` 13 → 16 — `choice` никогда не в чеке, группировка `groupKey` в диалоге,
  `configText` выводится как есть;
- прежние `PreorderQueueTest` 10 и `OrderScanRouterTest` 4 — без изменений, проходят.

`lintDebug` — **0 errors, 108 warnings**.

### Ручной чек-лист на терминале (не выполнялся — нет доступа к кассе)
| Пункт | Статус |
|---|---|
| заказ из PWA виден в баннере ≤ 10 с | BLOCKED (нужна касса + backend Stage A–C/S1–S3) |
| «Принять» → у гостя «Принят» | BLOCKED |
| «Готовится» → у гостя «Готовится ~N мин» | BLOCKED |
| авто-«Готов» по таймеру без действий кассира | BLOCKED (экран перечитывает заказ каждые 10 с) |
| «В чек» → позиции и платные опции в корзине | BLOCKED |
| SELL → `mark-paid` → у гостя «Оплачен» | BLOCKED |
| бонус гостя: строка «Бонус…» и итог в панели | BLOCKED |
| QR карты лояльности по-прежнему открывает лояльность (регресс) | BLOCKED (логика не менялась, покрыта тестом) |
| заказ другой точки → 403, ничего не добавлено | BLOCKED (текст ошибки готов) |

### Известные ограничения
1. **Подпись APK:** на машине сборки нет release-keystore, поэтому артефакт подписан debug-ключом.
   Повторить `assembleRelease` с `KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`
   (см. [RELEASE-KEY.md](./RELEASE-KEY.md)) — APK получит `CN=6.7 Coffee, OU=POS`.
2. `sc.preorder` вместо `sc.ord` (см. ниже) — требует однострочной правки backend Stage C.
3. ETA-редактирование (`POST /orders/:id/eta`) не реализовано (P1).
4. Опции без `evotorUuid` добавляются вручную: в строке остаётся только текст рецепта.
5. Backend с этой машины не проверялся; совместимость вниз сохранена («уточняем» вместо ETA,
   модификаторы без uuid — вручную, `benefit=null` — прежний вид панели).

---

## История: 162 / 5.3.3-preorder-queue

### Версии
| | |
|---|---|
| было | `161` / `5.3.2-preorder-pull` |
| стало | **`162` / `5.3.3-preorder-queue`** |
| база | ветка `feature/preorder-162-queue` от `feature/preorder-161-pull` (`f4de4d6`) |
| APK | `dist/sixthcup-evotor-5.3.3-preorder-queue-vc162.apk`, 6 151 295 байт |
| sha256 | `9855f26d9908b8c7e27aa084d88bfc24031798432b46e715f9c325d3fb9c540e` |
| подпись | `CN=6.7 Coffee, OU=POS` (release) |

### Новые / изменённые файлы
| Файл | Что |
|---|---|
| `data/PreorderQueue.kt` (новый) | подписи статусов, матрица кнопок, переходы, ETA по `serverNow`, сортировка очереди, тексты ошибок |
| `data/PreorderPull.kt` | модификаторы с `sendToReceipt=true` + `evotorUuid` — **отдельные позиции** по uuid из номенклатуры; без uuid — предупреждение, напиток добавляется; итог «Добавлено N поз. Сумма по кассе: X ₽» |
| `net/OrderApi.kt` | `Modifier.evotorUuid`, `Order.etaReadyAt/etaMinutes/serverNow`, `ActiveRow.etaReadyAt/serverNow` |
| `ui/orders/OrdersListActivity.kt` | карточки (код, статус, ETA, оценка, позиций), сортировка `draft→accepted→cooking→ready`, автообновление 10 с на переднем плане, pull-to-refresh, ввод 6 цифр |
| `ui/orders/OrderDetailActivity.kt` | ETA, карта, состав, «Оценка гостя», кнопки строго по статусу, экран «Оплачен», диалог подтверждения после «В чек» |
| `ui/MainActivity.kt` | баннер очереди в шапке (`● Заказ 123456 · 2 поз. · ~4 мин`), опрос `active` каждые 10 с в `onResume`…`onPause` |
| `data/CardSession.kt` | новый ключ `sc.preorder` (см. «Решение по `sc.ord`») |
| `sell/SellLauncher.kt` | передаёт `preorderId` из сессии в `extras.sc` |
| tests | `PreorderQueueTest` (10), `PreorderPullTest` 9 → 13 |

**Не менялись:** лояльность, `ReceiptValidator`, `DiscountCalc`, `FiscalLines`, `ReceiptPlan`, скидки, ваучеры, сканер QR карты, `ChoicePrintStore`, парсер конфигурации товара.

### Решение по `sc.ord` (важно, расхождение с п.1.6 промпта)
Промпт требует положить id предзаказа в `sc.ord`. Но в текущем backend `sc.ord` **уже занят**:
`SellHandler.queueRefOf()` → `bindQueueNumber(ord)` связывает чек с публичным номером очереди
(`queue_numbers`). Перезапись `ord` id предзаказа сломала бы эту привязку — реальный регресс.

Поэтому:
- `sc.ord` остаётся orderId **очереди** (без изменений),
- id предзаказа пишется **новым ключом** `sc.preorder` (≤64 символов),
- связка «SELL → предзаказ» уже работает через APK: после успешного SELL вызывается
  `POST /orders/:id/mark-paid` (161).

Для backend Stage C: читать `sc.preorder` (одна строка) — либо принять оба варианта и различать
по формату/наличию в `preorders`.

### Тесты (XML-отчёты)
`./gradlew :app:testDebugUnitTest` → **548 pass / 0 fail / 0 errors**, 64 класса (было 534).
- `PreorderQueueTest` 10 — статусы, матрица кнопок (таблица 2.3), переходы, ETA от `serverNow`,
  отсутствие ETA → «уточняем», `ready` важнее ETA, баннер и «ещё N», сортировка draft-first,
  тексты ошибок 401/403/404/409/410, истечение срока;
- `PreorderPullTest` 13 — цена из номенклатуры, позиция без uuid пропускается,
  товар не найден, смешанный заказ, дедуп повторного «В чек», рецепт, **платная опция с uuid отдельной
  позицией**, бесплатная без uuid — только предупреждение, опция без uuid — предупреждение + напиток,
  опция не найдена в кассе, количество ≥ 1;
- `OrderScanRouterTest` 4 — карта лояльности/цифры **не** распознаются как order QR (регресс-защита).

### Сборка
`lintDebug` — **0 errors, 109 warnings** (первая попытка дала `NewApi` на шаблоне `XXX`: исправлено
ручным разбором смещения зоны, minSdk 23 сохранён).

### Ручной чек-лист на терминале (не выполнялся — нет доступа к кассе)
| Пункт | Статус |
|---|---|
| заказ из PWA виден в баннере ≤ 10 с | BLOCKED (нужна касса + backend Stage A–C) |
| «Принять» → у гостя «Принят» | BLOCKED |
| «Готовится» → у гостя «Готовится ~N мин» | BLOCKED |
| авто-«Готов» по таймеру без действий кассира | BLOCKED (экран перечитывает заказ каждые 10 с) |
| «В чек» → позиции и платные опции в корзине | BLOCKED |
| SELL → `mark-paid` → у гостя «Оплачен» | BLOCKED |
| QR карты лояльности по-прежнему открывает лояльность | BLOCKED (логика не менялась, покрыта тестом) |
| заказ другой точки → 403, ничего не добавлено | BLOCKED (текст ошибки готов) |

### Известные ограничения
1. Баннер реализован **в шапке** (chip «Заказы» превращается в полосу с текстом), а не отдельной
   полосой под шапкой — минимальные правки `MainActivity`, без редизайна.
2. ETA-редактирование (`POST /orders/:id/eta`) не реализовано (P1).
3. Опции без `evotorUuid` добавляются вручную: в строке остаётся только текст рецепта.
4. `sc.preorder` вместо `sc.ord` — требует однострочной правки backend Stage C (см. выше).
5. Backend с этой машины не проверялся.
