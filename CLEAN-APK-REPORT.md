# CLEAN-APK-REPORT — чистка репозитория кассы

Дата: 30.10.2026 · Базовый HEAD: `962b948` (5.2.12-addons-volume / 137)
Инвентаризация с фактами: `CLEAN-AUDIT-APK.md`.

## 1. Итог одной строкой

Из репозитория ушли 39 устаревших `.md` и 84 строки мёртвого кода. Бизнес-логика
(лояльность, фискализация, `CatalogApi.merge`, `SheetToppings`, scanner, `CardSession`) не менялась.
Тесты: **287 / 0** до и после. `lintDebug`: 0 ошибок. Версия не поднималась.

## 2. Что удалено

**Код (84 строки, 4 файла):**

| Файл | Символ | Доказательство |
|---|---|---|
| `ui/CatalogAdapter.kt` | `qtyPill`, `applyQty` | по 1 вхождению (только объявление), остаток старой плитки |
| `ui/MainActivity.kt` | `buildSearchRow`, поля `searchEdit`/`searchHost` | 1 вхождение у функции; поля только объявлены/обнулялись |
| `integration/DiscountIntegrationService.kt` | `val floorNeeded = false` | 1 вхождение, остаток после перехода на `FiscalCalc.split` |

**Документация (39 файлов):** журналы шагов `STEP-*-PROGRESS` (6), одноразовые инструкции
`AGENT-APPLY-*`, `APPLY-*` (4), заметки старых версий `PATCH-NOTES`, `VERIFY`, `FIX-*`, `FIXES_REPORT`
(4), выполненные планы и приёмки `DESIGN-*`, `PLAN-INDEX-UI-FULL-PORT` (4), `AUDIT_REPORT`,
`README-CLOUD-PHASE0`, `BACKEND-CATALOG-ISGROUP`, `CHANGELOG-BLOCK-01…17` (17),
`docs/ONLINE-LOYALTY-CHECKLIST` (сам помечен HISTORICAL), `docs/DOC-ALIGNMENT`.
У каждого — **0 входящих ссылок** (проверено по всему репозиторию). Полная таблица — в аудите.

## 3. Что оставлено осознанно

- `README.md`, `INSTALL.md`, `BACKEND-API.md`, `CHANGELOG-CASHIER-UX.md` — на них ссылаются код и документы.
- `docs/*` — контракты (`LOYALTY-EXTRAS-CONTRACT`, `QUEUE-NUMBER`, `RELEASE-KEY`, `CASHIER-APP`, `BARISTA-FLOW-V2`, `CATALOG-TOPPINGS-UPSELL`) и рабочие инструкции.
- `IMPLEMENTATION-*.md` (13 отчётов) — история выпусков вместе с результатами проверок на терминале.
- `STEP-LOYALTY-APK-PROGRESS.md`, `STEP-CASHIER-LIVE-CARD-PROGRESS.md` — на них ссылаются соответствующие `IMPLEMENTATION-*-REPORT.md`.
- `CHANGELOG-4.x/5.x` — release notes выпущенных версий.
- `2n.md` — неотслеживаемый файл владельца, не тронут.

## 4. Отложено (DEFER)

| Что | Почему |
|---|---|
| неиспользуемый параметр `card` у `openLoyaltyOrSend` | 5 вызовов в upsell-цепочке, риск больше пользы |
| неиспользуемые параметры `line`/`idx` в `FiscalDiscount.kt` | файл в списке «не трогать» |
| два пути добавок (`OptionPicker` / `#opt-only`) | мёртвость не доказана без терминала |
| битая ссылка в `ThumbLoader.kt` на `docs/CASHIER-PHOTOS.md` | файла нет и до чистки; нужен выбор владельца, какой документ актуален |
| `docs/PHOTOS-ON-CASHIER.md`, `docs/REPORT-CASHIER-UI.md`, `docs-TZ.md` | содержательные, но без входящих ссылок — решение за владельцем |

## 5. Проверки

```
./gradlew :app:testDebugUnitTest   -> 287 pass / 0 fail  (как до чистки)
./gradlew :app:lintDebug           -> BUILD SUCCESSFUL, 0 errors
./gradlew :app:compileDebugKotlin  -> BUILD SUCCESSFUL
```

`versionCode` не менялся (137): чистка не является релизом.

## 6. Diff

```
39 файлов удалено (только .md)
4 файла изменено: CatalogAdapter.kt (-10), MainActivity.kt (-70), DiscountIntegrationService.kt (-1),
                  CLEAN-AUDIT-APK.md / CLEAN-APK-REPORT.md (новые)
```

Push не делался — по условию задачи ждём команды владельца.
