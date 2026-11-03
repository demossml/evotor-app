# IMPLEMENTATION REPORT — Product configuration end-to-end

Линия: backend `sixth-cup-main_6` → правки; APK `evotor-app-main_9/_10` (идентичны, versionCode 150) → правки.

## Контракт
`GET /api/devices/products/:id/configuration?storeUuid=` — см. `docs/DEVICES-PRODUCT-CONFIGURATION.md` (backend). Формат = тот, что уже парсит APK 150. Эспрессо: `milk:null, additions:null`. Капучино: milk (обычное — UI-only, `sendToReceipt:false`) + additions. uuid строго из `product_store_links` точки.

## Файлы
**Backend** (`backend/src`): `modules/catalog/productCard.ts` (новый, builder+readiness), `modules/catalog/addonScheme.ts` (sellable/notReadyReason), `modules/devices/routes.ts` (route configuration; в catalog — `sellable`, `notReadyReason`, `features`, `notReady[]`), `modules/admin/routes.ts` (`/catalog/features`, `/catalog/readiness`, `/catalog/product/:id/card`, `POST /modifier-schemes/:id/apply`), `lib/catalogFeatures.ts`. Старый `/catalog/product/:id` убран (второй endpoint не плодим).
**Admin** (`frontend-admin/src`): `lib/schemeBlocks.ts`, `components/SchemeBlocksEditor.tsx`, `pages/MenuGroups.tsx` (блоки «Молоко (обязательно, если назначено)» / «Добавки (по желанию)», массовое назначение с предпросмотром), `pages/CatalogReadiness.tsx` + вкладка «Готовность кассы» в `pages/Menu.tsx` (причины + статус «выгружено в Эвотор»).
**APK** (`app/src/main/java/ru/sixthcup/evotor`): `data/ProductConfig.kt`, `net/ProductConfigApi.kt`, `data/ProductConfigCache.kt`, `data/ProductConfigGuard.kt`, `data/ConfigDiagnostics.kt`; `app/build.gradle.kts`.
Миграции БД не добавлялись.

## Версии APK
versionCode **151**, versionName **5.2.25-config-readiness** (было 150).

## Тесты
- Backend: vitest 82 файла / **492 pass**, tsc OK.
- Admin: vitest 9 файлов / **46 pass**, tsc OK, vite build OK.
- APK: Gradle/Android SDK здесь недоступны → **assemble и Gradle-тесты НЕ запускались**. Чистая логика (ProductConfig*, ConfigDiagnostics, DrinkConfig, новый ProductConfigContractTest) скомпилирована kotlinc 2.0.21 с org.json-шимом, **64/64 pass** (JUnit-тесты через reflection-раннер).

## PHASE V
| # | Проверка | Итог |
|---|---|---|
| 1 | Route существует, fixture даёт volumes/milk/additions | ok (products-configuration*.test) |
| 2 | Эспрессо: milk пусто | ok |
| 3 | Капучино: milk и addons непусты | ok |
| 4 | Catalog витрина не сломана | ok (device-catalog тесты зелёные, 492/492) |
| 5 | Modifier-товары не на витрине | ok (hideFromMenu-тесты) |
| 6 | APK sheet вызывает configuration | ok по коду (`MainActivity` → `ProductConfigApi.fetch`); UI не запускался |
| 7 | Нулевое молоко не создаёт line | ok (`ProductConfigContractTest`, `DrinkConfig.receiptLines`) |
| 8 | Readiness API/UI отдаёт reason | ok (API-тест + SSR-тест UI) |
| 9 | Архивы собираются, структура как у исходников | backend/admin ok (сборка+тесты); APK — **не собирался** |
| 10 | Отчёт с путями и versionCode | ok |

## configErrors=links
Если у объёма нет `product_store_links.evotor_uuid` на данной точке — это **проблема данных**, касса права, блокируя продажу. Код показывает причину («Объёмы не привязаны к Эвотору на этой точке — продажа запрещена»); экран «Готовность кассы» в админке перечисляет те же позиции с `no_store_link` / `no_evotor_uuid`. Чужой uuid не подставляется. Лечение: синхронизировать/привязать товары точки в админке.

## ASSUMPTIONS
1. Формат ответа — формат APK 150, не массивы из примера промпта (иначе ломали бы рабочий парсер). Отсутствие блока = `null`.
2. Молоко/добавки — одна схема с `group_key`, не отдельные таблицы (миграции только аддитивные, их нет).
3. `serverCatalog` по умолчанию выключен; APK держит `SERVER_CATALOG_ALLOWED=false`, витрина как сейчас.
4. При 404 route APK показывает ошибку/старое поведение 150 (отдельного donor-fallback не добавлялось).
5. Фикстура `addons-catalog-fixture.json` в APK-тестах не обновлялась (изменения backend аддитивные).

## Готово к заливке
**Backend + admin: да. APK: не проверен сборкой Android (нет SDK) — собрать и прогнать `./gradlew testDebugUnitTest assembleDebug` перед установкой.**
Общий вердикт: готово к заливке: **да для backend, условно для APK** (критичных fail по PHASE V нет, но п.9 для APK не подтверждён).
