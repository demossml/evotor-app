# CLEAN-AUDIT-APK — инвентаризация и чистка репозитория кассы

Дата: 30.10.2026 · HEAD до чистки: `962b948` (5.2.12-addons-volume / 137)
Только факты: путь, размер, первая строка, число входящих ссылок.

## 1. Состояние на входе

| | |
|---|---|
| `git status` | чисто, кроме неотслеживаемого `2n.md` (файл владельца — не трогал) |
| versionCode / versionName | 137 / `5.2.12-addons-volume` |
| `.md` в корне | 77 |
| `.md` в `docs/` | 12 |
| Kotlin-файлов (`app/src`) | 90 |

Как считались ссылки: поиск имени файла по всем `.md`, `.kt`, `.kts`, `.xml`, `.yml`, `.json`
репозитория (исключая `build/`, `.git/`), без учёта самого файла.

## 2. Дубли, мёртвые файлы, флаги (PHASE 0)

| Проверка | Результат |
|---|---|
| Одноимённые классы в двух файлах | **нет** (все объявления уникальны) |
| Файлы `*Backup*`, `*Old*`, `*Copy*`, `*2.kt` | **нет** |
| Файлы, чьи типы нигде не упоминаются | 1: `integration/ReceiptPrintExtraService.kt` — **живой** (регистрируется в `AndroidManifest.xml`, ссылка не из Kotlin) |
| Закомментированные блоки > 15 строк | **нет** |
| `TODO`/`FIXME`/«удалить» в коде | **нет** (единственное вхождение — слово в комментарии про кнопку «удалить» в UI комбо) |
| Feature-флаги всегда `false` | **нет** (`featured = false` — дефолт поля данных, не флаг) |

## 3. Мёртвый код, который удалён (PHASE 2)

Доказательство мёртвости — ровно одно вхождение имени в файле (объявление) по `grep`.

| Файл:строка | Символ | Вхождений | Что это |
|---|---|---|---|
| `ui/CatalogAdapter.kt:101` | `qtyPill(ctx, density)` | 1 (объявление) | остаток старой плитки с плашкой «×N»; заменён на `ProductVH` |
| `ui/CatalogAdapter.kt:112` | `applyQty(v, uuid)` | 1 | пара к `qtyPill`, никем не вызывалась |
| `ui/MainActivity.kt:851` | `buildSearchRow(m)` | 1 | строка поиска из старой вёрстки; в текущем UI поиск собирается в `renderOrder` |
| `ui/MainActivity.kt:848` | поле `searchEdit` | 1 (объявление) | существовало только для удалённого `buildSearchRow` |
| `ui/MainActivity.kt:845` | поле `searchHost` | 2 (объявление + `= null`) | записи были, чтений нет |
| `integration/DiscountIntegrationService.kt:105` | `val floorNeeded = false` | 1 | мёртвый локальный остаток после перехода на `FiscalCalc.split` |

Итого удалено кода: **84 строки**.

## 4. `.md`: удалено 39, оставлено 50

Причина удаления у всех одна из трёх: (а) рабочий журнал шага, чьё содержимое уже сведено в
`IMPLEMENTATION-*-REPORT.md`; (б) одноразовая инструкция агенту/владельцу про уже выпущенную
версию; (в) `BLOCK-01…17` — поштучные журналы переноса UI, сведённые в
`CHANGELOG-CASHIER-REDESIGN-4.6.7.md`. Входящих ссылок у каждого — **0**.

| Удалено | Байт | Назначение | Ссылок |
|---|---|---|---|
| STEP-BENEFIT-PROMPT-ONCE-PROGRESS.md | 4798 | журнал шага (итог — в IMPLEMENTATION-CLIENT-SCREEN-REPORT.md) | 0 |
| STEP-CLIENT-SCREEN-PROGRESS.md | 3911 | журнал шага | 0 |
| STEP-FISCAL-DISCOUNT-PROGRESS.md | 5063 | журнал шага | 0 |
| STEP-GIFT-VOUCHER-PROGRESS.md | 4629 | журнал шага | 0 |
| STEP-POSITION-DISCOUNT-PROGRESS.md | 3847 | журнал шага | 0 |
| STEP-VCH-APK-PROGRESS.md | 6359 | журнал шага | 0 |
| AGENT-APPLY-ANDROID.md | 753 | инструкция «накатить архив» (разово) | 0 |
| AGENT-APPLY-INDEX-UI.md | 3175 | разовое задание агенту (4.9.0) | 0 |
| APPLY-PHOTOS-AUTH.md | 390 | заметка версии 4.6.5 | 0 |
| APPLY-PWA-CASHIER.md | 3397 | разовый патч PWA 4.6.3 | 0 |
| PATCH-NOTES-4.6.6.md | 1125 | заметка версии 4.6.6 | 0 |
| VERIFY.md | 1491 | чеклист 4.6.7-audit-verified | 0 |
| FIX-4.9.4-TOPPINGS.md | 1921 | разовый отчёт фикса 4.9.4 | 0 |
| FIXES_REPORT.md | 1425 | разовый отчёт | 0 |
| DESIGN-ACCEPTANCE-4.6.7.md | 1579 | приёмка 4.6.7 | 0 |
| DESIGN-PORT-FROM-INDEX.md | 2818 | план переноса (выполнен) | 0 |
| PLAN-INDEX-UI-FULL-PORT.md | 33837 | план переноса (выполнен), самый большой мусор | 0 |
| README-CLOUD-PHASE0.md | 297 | заметка фазы 0 | 0 |
| AUDIT_REPORT.md | 4184 | аудит 4.6.5 | 0 |
| BACKEND-CATALOG-ISGROUP.md | 780 | задача бэкенду (реализована в 4.9.14) | 0 |
| docs/ONLINE-LOYALTY-CHECKLIST.md | 1208 | сам помечен `# HISTORICAL` | 0 |
| docs/DOC-ALIGNMENT.md | 826 | сверка «as of» без ссылок | 0 |
| CHANGELOG-BLOCK-01…17 (17 файлов) | 329…1373 | журналы переноса UI по блокам | 0 |

## 5. `.md`: оставлено и почему

| Оставлено | Причина |
|---|---|
| README.md, INSTALL.md | точка входа проекта, на них ссылается `CHANGELOG-CASHIER-UX.md` |
| BACKEND-API.md | ссылка из кода: `net/ShiftApi.kt` |
| CHANGELOG-CASHIER-UX.md | ссылка из кода: `ui/MainActivity.kt` |
| CHANGELOG-5.0.0-evotor-receipt.md, CHANGELOG-5.1.0-addons-contract.md, CHANGELOG-CASHIER-UX-v2.md | ссылки из `docs/CASHIER-APP.md`, `docs/BARISTA-FLOW-V2.md` |
| docs/CASHIER-APP.md, docs/LOYALTY-EXTRAS-CONTRACT.md, docs/BARISTA-FLOW-V2.md | контракты, на них ссылаются код и тесты |
| docs/QUEUE-NUMBER.md, docs/RELEASE-KEY.md | действующие контракты (номер очереди, подпись) |
| docs/EVOTOR-CABINET-SETUP.md, docs/CATALOG-TOPPINGS-UPSELL.md, docs/PLAN-MODIFIERS.md, docs/PHOTOS-ON-CASHIER.md, docs/REPORT-CASHIER-UI.md | рабочие инструкции/описания текущего UI |
| CHANGELOG-4.6.1…5.x (release notes версий), CHANGELOG-POS-UI-v71, CHANGELOG-BARISTA-FLOW-V2 | история выпусков |
| IMPLEMENTATION-*.md (13), REPORT-DISCOUNT-SCROLL.md | отчёты по выпущенным версиям, включая результаты проверок на терминале |
| STEP-LOYALTY-APK-PROGRESS.md, STEP-CASHIER-LIVE-CARD-PROGRESS.md | на них ссылаются `IMPLEMENTATION-LOYALTY-APK-REPORT.md` и `IMPLEMENTATION-CASHIER-LIVE-CARD-REPORT.md` — удаление сломало бы ссылки |
| BACKEND-METHODS.md | ссылка из `BACKEND-API.md` |
| EVOTOR-FEASIBILITY.md, EVOTOR-SIZES-SETUP.md, docs-TZ.md | содержательные документы (ТЗ, настройка номенклатуры), не заметки |

## 6. Отложено (DEFER) — с причиной

| Что | Почему не удалено |
|---|---|
| Параметр `card` у `openLoyaltyOrSend(card)` не используется | сигнатура вызывается из 5 мест в цепочке upsell; правка ради одного предупреждения даёт больше риска, чем пользы |
| `FiscalDiscount.kt:154` (`line`), `:182` (`idx`) — неиспользуемые параметры лямбд | файл в списке «не трогать» (фискальный расчёт) |
| Два пути добавок (`OptionPicker` vs `#opt-only`) | grep не показал мёртвости; вырезать без терминала нельзя |
| `docs/PHOTOS-ON-CASHIER.md`, `docs/REPORT-CASHIER-UI.md`, `docs-TZ.md` | описывают действующие вещи, но ссылок нет — решение за владельцем |
| `ThumbLoader.kt` ссылается на `docs/CASHIER-PHOTOS.md` | файла с таким именем нет (ссылка была битой и до чистки) — требует решения, какой документ считать актуальным |
