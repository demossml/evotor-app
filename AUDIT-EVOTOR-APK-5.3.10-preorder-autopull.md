# AUDIT Evotor APK 6.7 Coffee

**Версия:** `versionCode = 169`, `versionName = 5.3.10-preorder-autopull`  
**HEAD:** `e0fea77` (ветка `main`)  
**Дата:** 2026-11-15  
**Режим:** read-only, статический аудит исходников. Код не изменялся.  
**Объект:** `ru.sixthcup.evotor`, `applicationId = ru.sixthcup.evotor`.

---

## Executive summary

- **CRITICAL-дефектов не найдено.** Найдено два HIGH, которые могут привести к неверной льготе или обходу обязательной схемы товара.
- **Главный разрыв контракта:** поле `canRedeem` из `POST /api/devices/loyalty/resolve` в APK **не читается вообще** (0 вхождений). Право списания выводится из `kind` (по форме кода), наличия `reservationId` и `freeAvailable > 0`.
- **Второй HIGH:** при неудачном запросе `GET /products/{id}/configuration` (нет сети, 5xx, 404, нет кэша) APK всё равно открывает лист товара и **разрешает «В чек»**, потому что `ProductConfigGuard.blockers(null)` возвращает пустой список. Обязательное молоко/добавки можно обойти.
- **Расхождение допусков резерва:** UI считает резерв живым 120 секунд после `reservationExpiresAt`, а расчёт чека — 300 секунд. Экран и фискальный чек могут разойтись.
- **Поле `kind` от backend игнорируется:** APK определяет `token/code` по наличию точки в строке (`code.contains('.')`), а не по ответу сервера.
- **Плюсы, которые надо сохранить:** офлайн-QR, строгий разбор configuration, проверка store/product/schema, запрет синтетических `mod:`-позиций, пол 1 ₽, повторный `resolve` после продажи, идемпотентный `mark-paid`.
- **Готовность к прод-кассе: 6/10.** Основные сценарии закрыты, но два HIGH и расхождение допусков требуют исправления до доверия деньгам/лояльности на всех точках.

### Топ-5 проблем

1. `canRedeem` не читается и не проверяется ни в UI, ни в `SellLauncher`, ни в `DiscountIntegrationService`.
2. Ошибка configuration не блокирует продажу — лист открывается с пустыми `blockers`.
3. Разные grace-периоды резерва: 120 с в UI, 300 с в расчёте чека.
4. `kind` берётся из формы кода, а не из ответа `resolve`.
5. Ваучеры показываются без живого резерва; выбор такого ваучера не даёт скидки. Дополнительно окно клиента открывается после каждой новой карты, даже когда льгот нет.

---

## Inventory

| Параметр | Значение |
|---|---|
| `applicationId` | `ru.sixthcup.evotor` |
| `versionCode` | `169` |
| `versionName` | `5.3.10-preorder-autopull` |
| `minSdk` | 23 |
| `targetSdk` | 30 |
| `compileSdk` | 34 |
| Kotlin/JVM | Kotlin Android, JVM 17 |
| Evotor SDK | `com.github.evotor:integration-library:v0.6.27` |
| Main Kotlin-файлов | 88 |
| Test Kotlin-файлов | 70 |
| Всего `.kt` | 158 |
| Ключевые зависимости | `core-ktx 1.12.0`, `appcompat 1.6.1`, `material 1.11.0`, `recyclerview 1.3.2`, `swiperefreshlayout 1.1.0`, `coroutines-android 1.7.3` |
| Release-подпись | внешний keystore; без него release подписывается debug-ключом (заложено в `app/build.gradle.kts`) |

### Пакеты

| Пакет | `.kt` |
|---|---:|
| `data` | 57 |
| `ui` (+ `ui/orders`) | 15 |
| `net` | 8 |
| `integration` | 2 |
| `scanner` | 2 |
| `sell` | 2 |
| `shift` | 1 |
| корень (`SixthCupApp.kt`) | 1 |

### Крупнейшие файлы

| Файл | Строк |
|---|---:|
| `ui/MainActivity.kt` | 5826 |
| `ui/CatalogAdapter.kt` | 599 |
| `net/CatalogApi.kt` | 552 |
| `ui/OptionPicker.kt` | 524 |
| `ui/orders/OrderDetailActivity.kt` | 391 |
| `net/ProductConfigApi.kt` | 351 |
| `data/CatalogCache.kt` | 317 |
| `integration/DiscountIntegrationService.kt` | 306 |
| `sell/SellLauncher.kt` | 284 |

### Ключевые модули

- Карта/лояльность: `net/LoyaltyApi.kt`, `data/CardSession.kt`, `data/CardProgress.kt`, `data/ClientOffers.kt`, `data/DiscountCalc.kt`.
- Каталог: `net/CatalogApi.kt`, `data/CatalogCache.kt`, `data/ServerCatalog.kt`, `net/StoreUuidResolver.kt`, `net/DeviceHeaders.kt`.
- Конфигурация товара: `net/ProductConfigApi.kt`, `data/ProductConfig.kt`, `data/ProductConfigCache.kt`, `data/ProductConfigGuard.kt`, `data/VolumeResolver.kt`, `ui/ConfigOptsMapper.kt`, `ui/OptionPicker.kt`, `ui/SheetSections.kt`, `ui/SheetOptionsPolicy.kt`.
- Чек: `sell/SellLauncher.kt`, `sell/ReceiptPlan.kt`, `data/DrinkConfig.kt`, `data/CartLines.kt`, `data/ReceiptValidator.kt`, `data/FiscalDiscount.kt` (`FiscalCalc`), `integration/DiscountIntegrationService.kt`.
- Предзаказы/очередь: `net/OrderApi.kt`, `data/PreorderPull.kt`, `data/PreorderPullSession.kt`, `data/OrderBenefit.kt`, `data/PreorderPaid.kt`, `data/OrderQueue.kt`, `data/QueueNumber.kt`.
- Сканер: `scanner/ScannerReceiver.kt`, `scanner/ScannerHub.kt`.
- Смена: `net/ShiftApi.kt`.

### Документационный дрейф

- `README.md:3-6` всё ещё указывает `VersionCode 81` / `4.6.6`, тогда как сборка — `169 / 5.3.10-preorder-autopull`. Это не влияет на APK, но вводит в заблуждение при разборе инцидентов.
- `data/CatalogMode.kt:15` держит `SERVER_CATALOG_ALLOWED = false`, поэтому `features.serverCatalog` от backend фактически игнорируется. Это осознанное решение по комментарию, но контрактный флаг выглядит включённым на сервере и не работает на кассе.

---

## Grep-карта обязательных символов

| Символ | Найдено | Где / примечание |
|---|---|---|
| `canRedeem` | **НЕТ (0)** | Ни в main, ни в тестах. Ключевой разрыв контракта. |
| `freeStatus` | Да | `net/LoyaltyApi.kt:68`, `ui/MainActivity.kt:915-932`, `1161-1163`, `2888`, `3198-3199` |
| `ALREADY_RESERVED` | Да | `ui/MainActivity.kt:918`, `1161`; `data/CardSession.kt:21` |
| `reservationId` | Да | `net/LoyaltyApi.kt:65-66`, `sell/SellLauncher.kt:158/192`, `data/CardSession.kt:168` |
| `kind == "code"` / `kind == "token"` | Нет буквально | Есть `data/CardSession.kt:199` `kindOf(code) = if (code.contains('.')) "token" else "code"` |
| `ProductConfigApi` / `/configuration` | Да | `net/ProductConfigApi.kt:43/64`, `ui/MainActivity.kt:1317` |
| `SheetOptionsPolicy` | Да | `ui/SheetOptionsPolicy.kt:10`, `ui/MainActivity.kt:1792/2006/2149` |
| `SheetSections` | Да | `ui/SheetSections.kt:9`, `ui/OptionPicker.kt:301`, `ui/MainActivity.kt:1938` |
| `ConfigSheetDebug` | Да, `DEBUG_CONFIG_SHEET = true` | `ui/ConfigSheetDebug.kt:16`; панель добавляется в `ui/MainActivity.kt:1914` |
| `VolumeResolver` | Да | `data/VolumeResolver.kt:24`, `ui/MainActivity.kt:1328/1699` |
| `SheetToppings` | Да | `data/SheetToppings.kt:9`, `ui/MainActivity.kt:480` |
| `hideFromMenu` / `isModifier` | Да | `net/CatalogApi.kt:291/319/388`, `ui/MainActivity.kt:623/635/3536/3685` |
| `DeviceHeaders` / `StoreUuidResolver` / `X-Evotor-Store` | Да | `net/DeviceHeaders.kt:14-36`, `net/StoreUuidResolver.kt:52-82`, `net/CatalogApi.kt:182`, `net/ProductConfigApi.kt:70` |
| `SellLauncher` | Да | `sell/SellLauncher.kt:38`, `ui/MainActivity.kt:4248` |
| `FiscalDiscount` | Файл есть, объекта нет | `data/FiscalDiscount.kt` содержит `object FiscalCalc`; `priceWithDiscountPosition` — `sell/SellLauncher.kt:139-140` |
| `priceWithDiscount` | Да | `data/FiscalDiscount.kt:14`, `sell/SellLauncher.kt:139` |
| `ScannedCode` / `ScannerReceiver` / `handleScannedCode` | Да | `scanner/ScannerReceiver.kt:36/41`, `ui/MainActivity.kt:217-229`, `5714` |
| `CardSession` / `ClientScreen` | Да | `data/CardSession.kt`, `ui/MainActivity.kt:3943` (`openClientScreen`) |
| `showClient` | Нет | Метод называется `openClientScreen` |
| `rebuildItemSheet` / `rebindOpts` | Да | `ui/MainActivity.kt:1660`, `ui/OptionPicker.kt:187` |

---

## Contract alignment

| Бизнес-правило | Backend/PWA | APK | Статус |
|---|---|---|---|
| Code = показ прогресса, без списания | `canRedeem=false`, `freeAvailable` может быть честным | `kindOf(code) = "code"`, `redeemEnabled=false`, `ClientOffers.gift=false` без резерва | **ALIGNED по факту, DRIFT по контракту** — `canRedeem` не читается |
| Token (QR) = можно списать по правилам | `canRedeem=true`, резерв | `reservationId != null && freeAvailable > 0 && reserveAlive` | **DRIFT** — нет проверки `canRedeem` |
| `ALREADY_RESERVED` | отдельный статус | баннер кассиру | ALIGNED |
| `freeStatus=OFFLINE/ERROR` | сеть/ошибка | баннеры, `OFFLINE` QR уходит в чек без бонусов | ALIGNED |
| `freeAvailable` | сколько free доступно | используется для `gift`/`redeemEnabled` | ALIGNED, но без `canRedeem` |
| `reservationId` | hold | `sc.op`, extras | ALIGNED |
| Ваучер только QR + резерв | `voucherId` при `op` + token | `sc` пишет только при `reserved && kindOf(c)=="token"`; **UI показывает ваучеры без резерва** | **DRIFT (UI)** |
| Кэшбэк только при резерве | `cb` при `op` | `DiscountCalc.reserved` + `extrasCb` | ALIGNED |
| После продажи обновить карту | backend poll SELL | `refreshCardAsync()` сразу + poll 30 с | ALIGNED |
| `storeUuid` этой точки | query/header | `StoreUuidResolver` + `X-Evotor-Store-Uuid` + `X-Evotor-Device-UUID` | ALIGNED; APP_UUID отклоняется |
| `hideFromMenu` / `isModifier` | флаги каталога | merge скрывает по identity | ALIGNED |
| Configuration: обязательные схемы | `milk/additions/choices` | `ProductConfigGuard.blockers` при `cfg != null` | **DRIFT при ошибке fetch**: `blockers(null)=[]` |
| Цена и uuid добавок | сервер/номенклатура | цена из терминала, uuid обязателен для платной опции | ALIGNED |
| Синтетические `mod:` не в чеке | запрет | `Cart.add` отбрасывает; `ReceiptPlan` блокирует | ALIGNED |
| Подарок/ваучер не 0 ₽ | пол 1 ₽ | `FiscalCalc.GIFT_FISCAL_MIN = 1.00` | ALIGNED |
| `extras.sc` | `{appId:{sc}}+sc` | `CardSession.extras` дублирует оба ключа | ALIGNED |
| Смена: `from/to`, `month`, `revenueRub` | BACKEND-API | `ShiftApi` использует те же поля | ALIGNED |
| `/api/devices/orders/*` auth | вариант A: proxy + store, `X-Device-Token` не обязателен | APK не отправляет `X-Device-Token`, отправляет store/device/version | **UNKNOWN / зависит от backend**: без варианта A будет 401 |

---

## Scenario matrix (A–I)

| ID | Result | Risk | Evidence |
|---|---|---|---|
| A1. Определение `storeUuid` | Реализовано: prefs → build → Settings → provider → whoami | LOW | `net/StoreUuidResolver.kt:60-90` |
| A2. Исключение APP_UUID / чужой точки | `isStoreUuid` отклоняет APP_UUID | LOW | `net/StoreUuidResolver.kt:52-57` |
| A3. Catalog fetch: query + headers | `?storeUuid=` + `X-Evotor-Store-Uuid` + device/version | LOW | `net/CatalogApi.kt:176-185`, `net/DeviceHeaders.kt:24-36` |
| A4. Кэш каталога per-store | Ключ по store; **fallback на глобальный v1** | MEDIUM | `data/CatalogCache.kt:20-30`, `195-200` |
| B1. `hideFromMenu` / `isModifier` | Скрываются по identity; модификаторы не в ленте | LOW | `net/CatalogApi.kt:291-319/388` |
| B2. Категории / «Все» | `menu` из backend, `MenuTabs`; «Все» настраивается | LOW | `ui/MainActivity.kt:1080-1120` |
| B3. Фото | Только HTTPS; `ThumbLoader` отклоняет http | LOW | `ui/ThumbLoader.kt:68-72` |
| B4. Поиск | Удалён с витрины (`q = ""`, комментарий) | INFO | `ui/MainActivity.kt:1230-1240` |
| C1. Sheet при открытии | Лист открывается сразу, config грузится; при ответе — `rebuildItemSheet` | LOW | `ui/MainActivity.kt:1300-1360/1660` |
| C2. Парсинг volumes/milk/additions/choices | Строгий разбор, schemaVersion, store/product mismatch | LOW | `net/ProductConfigApi.kt:88-165` |
| C3. Сопоставление объёма | UUID → productId → size → name | LOW | `data/VolumeResolver.kt:24-58` |
| C4. Секции вертикально | `OptionPicker` принудительно VERTICAL | LOW | `ui/OptionPicker.kt:26-30` |
| C5. `configPresent` не отбрасывается | `SheetOptionsPolicy` учитывает config | LOW | `ui/SheetOptionsPolicy.kt:10-18` |
| C6. Смена объёма 250↔350 | `rebindOpts` сохраняет выбранное по key, иначе default | LOW | `ui/OptionPicker.kt:187-205` |
| C7. Zero milk без uuid | `zeroOption` → не отдельная позиция | LOW | `data/DrinkConfig.kt:40-44/56-75` |
| C8. Paid topping без uuid | `available=false`, не выбрать | LOW | `ui/ConfigOptsMapper.kt:50-68` |
| C9. Блокировка «В чек» | Блокирует при `cfg != null`; **не блокирует при `cfg == null`** | **HIGH** | `ui/MainActivity.kt:1429-1430/1801/1981/2054` |
| D1. Одинаковые напитки с разным молоком | Разные `cartKey` по signature | LOW | `ui/OptionPicker.kt:225-235`, `data/CartLines.kt:20-50` |
| D2. UUID каждой позиции | `ReceiptPlan` требует terminal item; synthetic блокируется | LOW | `sell/ReceiptPlan.kt:50-90` |
| D3. Подарок/1 ₽ | `FiscalCalc` пол 1 ₽; `priceWithDiscountPosition` | LOW | `data/FiscalDiscount.kt:36-90` |
| D4. `extras/sc/reservation` | `CardSession.extras` + `SetExtra` | LOW | `data/CardSession.kt:142-190`, `sell/SellLauncher.kt:190-205` |
| D5. После SELL | Корзина/choice/pending очищаются, карта refresh, post-pay | LOW | `ui/MainActivity.kt:5740-5775` |
| E1. QR и ручной код | Оба идут в `resolve` | LOW | `ui/MainActivity.kt:229-245/884-945` |
| E2. `kind` / `canRedeem` | `kind` выводится из формы; `canRedeem` отсутствует | **HIGH** | `net/LoyaltyApi.kt:58-75`, `data/CardSession.kt:199` |
| E3. Free только по `canRedeem` | Не проверяется; code блокируется через `manual` | **HIGH** | `data/CardProgress.kt:53`, `sell/SellLauncher.kt:181` |
| E4. `ALREADY_RESERVED` | Баннер кассиру | LOW | `ui/MainActivity.kt:918` |
| E5. ClientScreen вместо цепочки | Один BottomSheet | LOW | `ui/MainActivity.kt:3943` |
| E6. Тот же QR | `CardReplaceRule` не сбрасывает | LOW | `data/CardReplaceRule.kt:25-38` |
| E7. Ваучеры/кэшбэк | Ваучеры показываются без резерва; выбор не даёт скидку | MEDIUM | `data/ClientOffers.kt:18-24`, `ui/MainActivity.kt:3950/3975` |
| E8. После чека нет stale | `refreshCardAsync` + poll 30 с | LOW | `ui/MainActivity.kt:4387-4410/5753` |
| F1. Официальный `ScannedCode` | Есть, плюс fallback-ключи | LOW | `scanner/ScannerReceiver.kt:36-65` |
| F2. Очередь сканов | `ScannerHub` AtomicReference | LOW | `scanner/ScannerHub.kt:6-14` |
| F3. Не глотает scan в диалоге | При `resolving` сохраняет и применяет после | LOW | `ui/MainActivity.kt:239-248` |
| G1. 403/503 proxy | Тексты `LoyaltyApi.resolveErrorText` | LOW | `net/LoyaltyApi.kt:90-102` |
| G2. Offline catalog | Кэш + плашка «данные от HH:MM» | LOW | `ui/MainActivity.kt:520-600` |
| G3. Таймауты configuration | 5 с connect / 10 с read; панель диагностики | LOW | `net/ProductConfigApi.kt:45-46` |
| H1. Shift contract | `from/to`, `month`, `revenueRub` | LOW | `net/ShiftApi.kt:55-65/95-105` |
| H2. Логи на backend | Локальный logcat/журнал; отправки `/devices/logs` нет | INFO | `SixthCupApp.kt`, `MainActivity` логирование |
| H3. 5″/7″/10″ | portrait + wide layout; runtime не проверен | **UNKNOWN** | `AndroidManifest.xml:23`, `MainActivity` Ds.Mode |
| I1. Хардкод токенов | Не найдено; Authorization инжектит Cloud | LOW | `net/LoyaltyApi.kt:14`, `net/CatalogApi.kt:186` |
| I2. Debug flags в release | `DEBUG_CONFIG_SHEET = true` | **MEDIUM** | `ui/ConfigSheetDebug.kt:16` |
| I3. WebView / cleartext | WebView нет; `usesCleartextTraffic=false` | LOW | `AndroidManifest.xml:16` |

---

## Defect register

| ID | Level | Title | File:line | Evidence | Suggested fix direction |
|---|---|---|---|---|---|
| D-01 | **HIGH** | `canRedeem` не читается и не проверяется | `net/LoyaltyApi.kt:58-75`, `data/CardProgress.kt:53`, `data/ClientOffers.kt:21-22`, `sell/SellLauncher.kt:181`, `integration/DiscountIntegrationService.kt:134` | В `parseResolve` нет поля; `redeemEnabled = !manual && reservationId != null && freeAvailable > 0 && reserveAlive`; `SellLauncher` считает скидку по `reserved/freeAvailable` | Добавить `canRedeem` в `CardInfo`, парсить; гейтить `gift`, `redeemEnabled`, `ClientOffers`, `SellLauncher`, `DiscountIntegrationService`, `CardSession.sc`; тесты token/code + `canRedeem=false` |
| D-02 | **HIGH** | Ошибка configuration не блокирует продажу | `ui/MainActivity.kt:1429-1430` → `1596-1599`, `1801`, `1981-1985`, `2054-2058`; `data/ProductConfigGuard.kt:65-66` | При `config=null` вызывается `deliver(... allowDirectAdd=false)`, открывается `showItemSheet`; `blockers(null)` → `emptyList()`, кнопка «В чек» активна | Ввести `configUnavailable` blocker (failed/404/5xx без cache) и не разрешать «В чек» до успешного fetch/повтора; 404 — отдельное продуктовое решение; тест «no config + required milk = blocked» |
| D-03 | MEDIUM | Разные grace-периоды резерва | `data/CardProgress.kt:33/52-53`, `data/DiscountCalc.kt:15/32` | UI: 120 с; расчёт чека: 300 с | Один источник допуска (например, `ReservationPolicy`), использовать в UI и фискальном расчёте; тест на границе |
| D-04 | MEDIUM | Поле `kind` от backend игнорируется | `net/LoyaltyApi.kt:58-75`, `data/CardSession.kt:199` | `parseResolve` не читает `kind`; `kindOf` = `code.contains('.')` | Парсить `kind`, хранить в `CardInfo`, использовать с fallback на форму кода |
| D-05 | MEDIUM | Ваучеры показываются без живого резерва | `data/ClientOffers.kt:23`, `ui/MainActivity.kt:3950`, `3975-3985`, `data/DiscountCalc.kt:54-62`, `data/CardSession.kt:168` | `vouchers = card.vouchers` без `reserved`/`token`; выбор сохраняется, но `voucherDiscount` требует `reserved`, `sc.voucherId` не пишется | Гейтить список ваучеров `reserved && kind=="token"`; иначе disabled + текст «нужен QR»; обновить `ClientOffersTest` |
| D-06 | MEDIUM | Окно клиента открывается после каждой новой карты | `ui/MainActivity.kt:3945`, `data/BenefitPromptGuard.kt:20-26` | `benefitGuard.ask(card.code, hasOffer = true)` — `hasOffer` всегда true | Передавать `ClientOffers.of(...).any`; если льгот нет — не открывать или показывать только прогресс по явному тапу |
| D-07 | MEDIUM | Отладочная панель configuration в release | `ui/ConfigSheetDebug.kt:16`, `ui/MainActivity.kt:1914` | `const val DEBUG_CONFIG_SHEET = true`, панель добавляется без `BuildConfig.DEBUG` | `BuildConfig.DEBUG && ConfigSheetDebug.DEBUG_CONFIG_SHEET` или `buildConfigField`; выключить в release |
| D-08 | MEDIUM | `sendToReceipt=false` не учитывается в выбранных опциях | `ui/OptionPicker.kt:207-213/246-248`, `data/DrinkConfig.kt:40-44` | `chosen()` фильтрует только `sellable`, `zeroOption`, uuid; `unitPrice()` суммирует все extras; `DrinkConfig` исключает `sendToReceipt=false` | Фильтровать `sendToReceipt` в `chosen()` и `unitPrice()` или явно обрабатывать print-only опции; тест «paid + sendToReceipt=false не меняет сумму» |
| D-09 | MEDIUM | Глобальный fallback кэша каталога | `data/CatalogCache.kt:20-30/195-200` | При отсутствии файла store читается `catalog_enrich_v1.json` | Разрешить v1 только для одноточечной миграции или проверять storeUuid внутри; тест multi-store |
| D-10 | LOW | Последний fallback сканера берёт любую строку | `scanner/ScannerReceiver.kt:59-65` | `for (k in b.keySet())` возвращает первый непустой String | Оставить whitelist ключей; неизвестные extras логировать и игнорировать |
| D-11 | LOW | TTL карты 10 минут может очистить карту в фоне | `data/CardSession.kt:45/61-64` | `get()` очищает сессию после 10 мин; poll работает только на видимом экране | Продлить TTL/не очищать при активной корзине; показывать «карта устарела» вместо тихого clear |
| D-12 | INFO | README устарел | `README.md:3-6` | `VersionCode 81 / 4.6.6` против `169 / 5.3.10` | Обновить README или убрать версию |
| D-13 | INFO | `ReceiptPlan` блокирует чек при synthetic lines | `sell/ReceiptPlan.kt:123` | `skippedSynthetic > 0` → блок; `Cart.add`/`restore` уже отбрасывают synthetic | Оставить как защиту; проверить, достижим ли путь |
| D-14 | INFO | `features.serverCatalog` фактически выключен | `data/CatalogMode.kt:15` | `SERVER_CATALOG_ALLOWED = false` | Согласовать с backend/продуктом; не считать флаг рабочим |
| D-15 | INFO | Заказы зависят от backend-варианта A | `net/OrderApi.kt:224-233`, `net/DeviceHeaders.kt:14-36`, `docs/BACKEND-ORDERS-AUTH-TZ.md` | APK не отправляет `X-Device-Token`; отправляет store/device/version | Задеплоить вариант A на backend или реализовать enroll; до этого orders могут давать 401 |

---

## canRedeem / freeStatus deep dive

Полный путь:

1. **Скан/ввод** → `handleScannedCode` (`ui/MainActivity.kt:229`) или ручной ввод → `resolve` (`ui/MainActivity.kt:884`).
2. **HTTP** → `LoyaltyApi.resolve` (`net/LoyaltyApi.kt:19-55`) → `parseResolve` (`net/LoyaltyApi.kt:58-75`).
3. **Модель** → `CardInfo` (`data/CardSession.kt:11-37`) **не содержит `canRedeem`**; содержит `freeAvailable`, `freeStatus`, `reservationId`.
4. **UI выгоды** → `CardProgress.of` (`data/CardProgress.kt:35-62`) и `ClientOffers.of` (`data/ClientOffers.kt:18-24`).
5. **Выбор кассира** → `ClientScreen`/`CheckoutChoice` (`ui/MainActivity.kt:3943-4060`, `data/CheckoutChoice.kt`).
6. **Чек** → `SellLauncher` (`sell/SellLauncher.kt:74-205`) и `DiscountIntegrationService` (`integration/DiscountIntegrationService.kt:134-205`).
7. **extras** → `CardSession.extras/sc` (`data/CardSession.kt:142-190`).
8. **Backend** → `SellHandler` (вне APK) должен сам проверить `canRedeem`, резерв и сумму.

### Что реально проверяет APK

- `manual = kind == "code"` → `redeemEnabled = false` для ручного кода.
- `reserved = reservationId != null && (expiresAt == 0 || expiresAt + grace >= now)`.
- `gift = freeAvailable > 0 && reserved`.
- `cashback = reserved && cashbackKopecks > 0`.
- `voucherId` пишется только при `reserved && kind == "token"`.

### Где разрыв

- Для **token** APK не проверяет `canRedeem`. Если backend вернёт `canRedeem=false`, но оставит `freeAvailable>0` и `reservationId`, APK покажет и применит подарок/кэшбэк.
- Для **code** APK безопасен по факту: `kindOf` = code, `reservationId` по контракту null, `redeemEnabled=false`, `gift=false`.
- `freeStatus` обрабатывается (`OFFLINE`, `ALREADY_RESERVED`, `ERROR`), но не является заменой `canRedeem`.
- `kind` от backend не читается; если формат QR изменится (token без точки) или ручной код будет содержать точку, классификация сломается.

**Вывод:** текущий backend, вероятно, не даёт эксплуатировать разрыв, потому что выдаёт резерв только при разрешённом списании. Но контракт `canRedeem` не enforced на клиенте; это HIGH для денежного пути и обязательный фикс перед расширением правил лояльности.

---

## Sheet/configuration deep dive

### Сильные стороны

- `ProductConfigApi.parse` проверяет `schemaVersion`, `storeUuid`, `productId`, отклоняет чужую точку/товар (`net/ProductConfigApi.kt:100-150`).
- `ProductConfigCache` хранит конфигурацию по `storeUuid + productId`, TTL 24 ч, не перезаписывает пустым ответом (`data/ProductConfigCache.kt:14-55`).
- `VolumeResolver` сопоставляет объём по uuid → productId → size → name (`data/VolumeResolver.kt:24-58`).
- `ProductConfigGuard` блокирует обязательное молоко, недоступные объёмы, повреждённый `choices`, `configErrors=links` (`data/ProductConfigGuard.kt:65-110`).
- `OptionPicker` вертикальный; `SheetSections` — единый источник секций (`ui/OptionPicker.kt:26-30`, `ui/SheetSections.kt:9-30`).
- `DrinkConfig` не отправляет zero-milk и `sendToReceipt=false` отдельными позициями (`data/DrinkConfig.kt:40-44`).

### Главный дефект

При `config=null` (сеть/5xx/404/нет productId/storeUuid/нет кэша):

- `deliver("config_fallback_catalog", allowDirectAdd=false)` (`ui/MainActivity.kt:1429-1430`);
- `openProductLegacy` при `allowDirectAdd=false` вызывает `showItemSheet` (`ui/MainActivity.kt:1596-1599`);
- `configBlocked = ProductConfigGuard.blockers(pendingConfigFor(start), ...)` → `blockers(null)` = `emptyList()` (`data/ProductConfigGuard.kt:65-66`);
- `paint()` разрешает кнопку, если `configBlocked + picker.blockers()` пусто (`ui/MainActivity.kt:1981-1985`);
- `addBtn` повторно проверяет только `pendingConfigFor(variant)` (`ui/MainActivity.kt:2054-2058`).

Итог: обязательное молоко/добавки можно не выбрать, если configuration не пришла. Это противоречит комментарию «не продаём вслепую» и `ProductConfigPolicy`.

### 404 отдельно

`config_unsupported` (`ui/MainActivity.kt:1397`) и `ConfigDiagnostics.hint` (`data/ConfigDiagnostics.kt:80-90`) явно говорят «окно работает по каталогу». Если backend поддерживает configuration, но отдаёт 404 на конкретный товар, это обход схемы. Нужно продуктовое решение: либо 404 = legacy-путь (тогда ответственность на backend), либо 404 = блок.

---

## Sell / fiscal deep dive

### Что сделано правильно

- `Cart.add` не принимает `mod:`-позиции (`data/Cart.kt:72-80`), `restore` их отфильтровывает (`data/Cart.kt:230-245`).
- `ReceiptValidator` сверяет опции с последним каталогом: удалённая/недоступная/без uuid/сменившая uuid опция блокирует (`data/ReceiptValidator.kt:29-80`).
- `ReceiptPlan` требует реальный товар терминала, не создаёт «ручную» позицию, блокирует чек при missing (`sell/ReceiptPlan.kt:50-125`).
- `SellLauncher` передаёт цену терминала, `setPriceWithDiscountPosition` только если ниже, пол 1 ₽ (`sell/SellLauncher.kt:120-145`, `data/FiscalDiscount.kt:36-90`).
- `extras.sc` дублируется в `appId` и плоском `sc`, `op/free/cb/kind` пишутся по правилам резерва (`data/CardSession.kt:142-190`).
- После успешного `SELL`: `PendingPayment`, очистка корзины/choice, `refreshCardAsync`, post-pay (`ui/MainActivity.kt:5740-5775`).
- `mark-paid` только после печати, с retry/идемпотентностью (`data/PreorderPaid.kt`, `ui/MainActivity.kt:4312-4348`).

### Остаточные риски

- `canRedeem` не проверяется в `SellLauncher`/`DiscountIntegrationService` (D-01).
- `OptionPicker.chosen()` игнорирует `sendToReceipt` (D-08).
- Grace-периоды 120/300 с (D-03).
- `ReceiptPlan.blockReason` блокирует чек при `skippedSynthetic > 0`; путь почти недостижим из-за `Cart.add`, но лучше покрыть тестом (D-13).

---

## Security

| Проверка | Результат |
|---|---|
| Хардкод токенов/секретов | Не найдено. `Authorization` инжектит Evotor Cloud (`net/LoyaltyApi.kt:14`, `net/CatalogApi.kt:186`). |
| `usesCleartextTraffic` | `false` (`AndroidManifest.xml:16`). |
| WebView | Не используется. |
| Debug-флаги | `ConfigSheetDebug.DEBUG_CONFIG_SHEET = true` (D-07). |
| Экспортированные компоненты | `MainActivity` exported (launcher), `DiscountIntegrationService`/`ReceiptPrintExtraService` exported по контракту Evotor, `ScannerReceiver` exported. Риск спуфинга сканера — LOW. |
| Логи | В логах есть `productId`, `store` (сокращённый), `kind`, `free`, `cb`; секретов нет. |
| `allowBackup` | `false`. |

---

## Post-sale / queue / preorder

- `PendingPayment` хранится 30 минут, переживает перезапуск (`data/PendingPayment.kt:9-25`).
- `confirmPendingOrder` выдаёт номер только после закрытия чека (`ui/MainActivity.kt:4371-4385`).
- `markPreorderPaid` вызывается только при `SaleConfirmStore.printedFor`, retry 3 попытки, `benefit_conflict` не ретраится (`ui/MainActivity.kt:4312-4348`, `data/PreorderPaid.kt`).
- `PreorderPull` не создаёт synthetic-позиции; модификатор без uuid уходит в `recipe` и предупреждение (`data/PreorderPull.kt:120-160`).
- `OrderApi` не отправляет `X-Device-Token`; см. D-15.

---

## What was NOT verified

- **RUNTIME_UNVERIFIED**: приложение не устанавливалось на терминал, live-касса не запускалась.
- Нет доступа к реальному Evotor Cloud proxy token и production backend; сетевые ответы не проверялись.
- Не проверены 5″/7″/10″ экраны, portrait/landscape, реальный сканер, принтер, `PrintExtra`.
- Не запускались `./gradlew :app:testDebugUnitTest` и `lint`; вывод тестов не подтверждён в этой сессии.
- Не проверены multi-store сценарии (переключение точки, старый `catalog_enrich_v1.json`).
- Не проверены реальные `canRedeem=false` ответы backend, TTL резерва и поведение `SellHandler`.
- Не проверен `/api/devices/orders/*` против текущего backend (401/403/вариант A).

---

## Recommended fix waves

### Wave 1 — деньги и лояльность (до прод-кассы)

1. **D-01**: добавить `canRedeem` в `CardInfo`, парсить в `LoyaltyApi`, гейтить `gift`, `redeemEnabled`, `ClientOffers`, `SellLauncher`, `DiscountIntegrationService`, `CardSession.sc`. Тесты: token `canRedeem=false`; code `canRedeem=false`; token `canRedeem=true`.
2. **D-02**: блокировать «В чек», если configuration не получена и нет достоверного кэша; отдельное решение по 404.
3. **D-03**: единый `ReservationPolicy` (120/300/серверный TTL) для UI и чека.

### Wave 2 — корректность UI

4. **D-05**: не показывать ваучеры без `reserved && token`; disabled + текст.
5. **D-06**: открывать `ClientScreen` только при `offers.any` (или по явному тапу).
6. **D-08**: учитывать `sendToReceipt` в `chosen()`/`unitPrice()`.
7. **D-07**: выключить debug-панель в release.

### Wave 3 — устойчивость

8. **D-09**: валидировать store в fallback-кэше.
9. **D-10**: whitelist ключей сканера.
10. **D-11**: не терять карту при активной корзине / показывать «устарела».
11. Обновить README/доки (D-12, D-14).

### Wave 4 — проверка на железе

12. Прогнать unit/lint и ручные сценарии на 5″/7″/10″, реальном сканере и принтере.
13. Проверить `canRedeem`, `ALREADY_RESERVED`, истёкший резерв, offline, multi-store, `mark-paid`.
14. Согласовать orders auth (D-15).

---

## Критерий «аудит завершён»

- [x] Версия зафиксирована: `169 / 5.3.10-preorder-autopull`, HEAD `e0fea77`.
- [x] Все сценарии A–I имеют статус.
- [x] Grep-символы §4 приложены.
- [x] `canRedeem`/путь лояльности разобран явно.
- [x] Есть таблица ALIGNED/DRIFT.
- [x] Defect register содержит file:line.
- [x] Код не изменён: в `git status` только untracked `2n.md` и `AGENT-PROMPT-VISUAL-AUDIT-HOME.md`; исходники не редактировались.

---

## Приложение: краткая карта доказательств

| Тема | Файл:строка |
|---|---|
| `canRedeem` отсутствует | `net/LoyaltyApi.kt:58-75`; поиск по репозиторию — 0 |
| `kind` по форме кода | `data/CardSession.kt:199` |
| UI redeem | `data/CardProgress.kt:52-53` |
| ClientOffers | `data/ClientOffers.kt:18-24` |
| Sell discount | `sell/SellLauncher.kt:181-205` |
| Discount service | `integration/DiscountIntegrationService.kt:134-152` |
| Config fallback | `ui/MainActivity.kt:1429-1430`, `1596-1599` |
| Blockers(null) | `data/ProductConfigGuard.kt:65-66` |
| Add button gate | `ui/MainActivity.kt:1981-1985`, `2054-2058` |
| Grace mismatch | `data/CardProgress.kt:33`, `data/DiscountCalc.kt:15` |
| Debug panel | `ui/ConfigSheetDebug.kt:16`, `ui/MainActivity.kt:1914` |
| Catalog cache fallback | `data/CatalogCache.kt:20-30`, `195-200` |
| Scanner fallback | `scanner/ScannerReceiver.kt:59-65` |
| Store/headers | `net/StoreUuidResolver.kt:52-90`, `net/DeviceHeaders.kt:14-36` |
| Shift contract | `net/ShiftApi.kt:55-105` |
