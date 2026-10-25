# 6.7 Coffee · Evotor — 5.1.0-addons-contract (versionCode 115)

Один контракт добавок для backend, админки и кассы. Оплата в APK не возвращалась,
формат чека и extras (`v:2`, `c`, `op`, `free`, `cb`, `sc`) не менялся.

## Что исправлено

| # | Проблема | Исправление |
|---|---|---|
| R1 | Каталог читал только `modifier_scheme_options`, а админка писала только `modifier_scheme_items`: правки схем в Studio/копии молча не доходили до кассы | Канон — `modifier_scheme_options`. `items` от старых сборок конвертируются в `options` при сохранении, легаси-таблица больше не пишется; `GET /modifier-schemes` отдаёт `options`, а `items` — проекция канона |
| R2 | `required` нельзя было задать в админке, а сохранение обнуляло его | Чекбокс «Обязательно» на опцию + бейдж обязательных групп; значение сохраняется в `options.required` и переживает повторное сохранение |
| R3 | Три разных словаря групп | Один словарь `milk / syrup / sugar / extra` в backend, `GET /api/admin/addon-groups` для админки, zod-валидация ключа. «Топпинг»/«Другое» сохраняются как `extra` (одна группа на кассе) |
| R4 | Studio-путь хардкодил группу и `max_count = 2` | Группа берётся через общий нормализатор, лимит — из группы (`extra` — 2, остальные — 1) |
| R5 | Два парсера одного JSON в APK: `maxCount` default 2 vs 1, цена `optDouble` vs `optString` | Единый `ToppingJson` для каталога из сети и локального кэша; кэш по-прежнему хранит все поля |

Дополнительно: пересохранение схемы больше не меняет `id` опций, поэтому строки корзины
не превращаются в `option_removed` после правки схемы в админке.

## Трактовка `maxCount`

`maxCount` — предел числа **разных** опций в группе, а не количество одной добавки
(две порции одного сиропа не продаются). Зафиксировано в `docs/CASHIER-APP.md`;
в коде поле читает `ToppingRules.groupLimit(...)`.

## Проверка

- `backend: npx vitest run` — 31 файл / 168 тестов;
- `frontend-admin: npm run build` + `npm test` — tsc чисто, 4 теста;
- `./gradlew :app:testDebugUnitTest :app:lintDebug assembleRelease` — 84 теста, lint без ошибок;
- сквозная фикстура `addons-catalog-fixture.json` одинакова в `evotor-app` и `sixth-cup`,
  проверяет путь админка → `GET /api/devices/catalog` → парсер APK → `ToppingRules` → `ReceiptValidator`.
