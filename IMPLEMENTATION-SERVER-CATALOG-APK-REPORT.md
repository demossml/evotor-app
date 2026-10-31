# IMPLEMENTATION — server-driven каталог (APK 5.2.14 / 140)

Промпт: `PROMPTS-SERVER-DRIVEN-ADDONS-APK.md` (STEP-A0…A7) + `CHANGES-FROM-PLAN.md` (F1–F8)
Репозиторий: `evotor-app` · ветка `main` · Backend **не менялся**

## 1. HEAD и версия

| | |
|---|---|
| HEAD до работы | `5499c4f` (5.2.13-addons-card / 138) |
| versionCode / versionName | **140** / `5.2.14-server-catalog` |
| Правило версии | `max(current+1, 140)` по F8 |

## 2. Как включается режим

`features.serverCatalog` в корне ответа `GET /api/devices/catalog`:

- `true` → витрину и состав листа строит `ServerCatalog` (новый путь);
- поле отсутствует или `false` → **прежний** `CatalogApi.mergeWithStats` (путь не удалён, F5).

Флаг сохраняется в офлайн-кэш, поэтому после перезапуска без сети режим тот же, что был.

## 3. Что сделано по шагам

| STEP | Изменение |
|---|---|
| A0 | База: 300 тестов / 0, 138 / 5.2.13-addons-card; путь витрины = `InventoryRepository.load` → `mergeWithStats` → `setProducts` |
| A1 | `CatalogApi.Features(serverCatalog)`, `Enrich.sellable` (default `true`), `Enrich.notReadyReason`; парсинг обоих, старый JSON не ломается |
| A2 | `data/ServerCatalog.kt`: плитки = продаваемые строки сервера, которые есть на терминале; `hidden_flag` / `not_sellable` / `missing_terminal` / `absent_on_server`; добавки — только с самого продукта |
| A3 | Обязательное молоко: `required` приходит из `toppings[]`, блокировку даёт существующий `ToppingRules.blockers` (покрыто тестом на данных server-режима). Платная добавка без uuid — не выбирается (`ToppingRules.ruleOf().sellable`), zero-опция позиции не создаёт |
| A4 | Лог `SC_CAT mode=server\|merge store=… sellable=… shown=… hidden_flag=… not_sellable=… missing_terminal=… absent_on_server=…` + плашка только при расхождениях |
| A5 | Кэш сохраняет `features.serverCatalog`, у каждого продукта `sellable` и `notReadyReason`; старый кэш читается как `serverCatalog=false`, `sellable=true` |
| A6 | Версия 140, тесты, `assembleRelease` (подпись релизным ключом) |
| A7 | Чеклист ниже |

## 4. Как в server-режиме получаются добавки

`ServerCatalog.applyEnrich` кладёт `toppings = e.toppings` — строго из объекта этого продукта в
`products[]`. Дальше в UI `sheetProduct()` в этом режиме возвращает продукт **как есть**, без
`SheetToppings.effective(...)` — донора по имени и соседнему размеру нет (F3). В merge-режиме
поведение прежнее, включая донора и `byGroupId`.

## 5. Проверки

| | |
|---|---|
| `:app:testDebugUnitTest` | **313 passed / 0 failed** (база 300, +13) |
| `:app:lintDebug` | BUILD SUCCESSFUL, 0 errors |
| `:app:assembleRelease` | BUILD SUCCESSFUL |
| APK | `app/build/outputs/apk/release/app-release.apk`, 5 720 400 байт |
| aapt | `versionCode='140' versionName='5.2.14-server-catalog'` |
| Подпись | `CN=6.7 Coffee, OU=POS, O=6.7 Coffee, L=Yekaterinburg, C=RU` |

Новые тесты: `ServerCatalogTest` (9), `CatalogPayloadParseTest` (+2), `CatalogCacheTest` (+2).

## 6. STEP-A7 — чеклист

```
[x] serverCatalog false → old path            (тернарник merged = serverShelf?.products ?: mergedResult.first)
[x] true → no name-merge for shelf/sheet      (identity: uuid → артикул → точное имя; byName не используется)
[x] zeroOption no Position                    (поведение прежнее: zero-опция не создаёт строку)
[x] paid topping no uuid disabled             (ToppingRules.ruleOf(): sellable = available && (!paid || uuid != null))
[x] Inventory missing → hidden + counter      (ServerShelf.missingOnTerminal, лог SC_CAT)
[x] SellLauncher still Inventory price        (файл не менялся)
[x] version ≥ 140                             (140 / 5.2.14-server-catalog)
```

## 7. Что НЕ менялось

`SellLauncher`, `scanner/*`, `FiscalCalc`/`FiscalLines`/`DiscountIntegrationService` (подарок 1 ₽),
лояльность (`CardSession`, `Choice`, ваучеры), `CatalogApi.mergeWithStats` (живой fallback),
backend и прод-данные. Проверено `git diff` по этим путям — пусто.

## 8. Что стоит знать при включении флага

1. Флаг включает **backend** (F5): пока он не выставлен, касса работает по-старому.
2. Порог по версии кассы — на стороне backend (`X-App-Version` / `versionCode ≥ 140`).
3. В server-режиме товары, которых нет в серверном каталоге, **не показываются** (`absent_on_server`
   в логе) — это осознанное следствие F4. Если на терминале есть позиции вне 6.7, их не будет в витрине.
4. `SheetContract` при пустых добавках у продукта, но непустом `byGroupId`, по-прежнему даёт
   `MAPPING_ERROR` — в server-режиме это сигнал о расхождении данных сервера, а не о поломке кассы.

## 9. Проверка на терминале (не выполнялась — устройства нет)

1. `logcat -s SC_CAT` → `mode=merge` на текущем backend (флаг выключен) — витрина как в 138.
2. После включения `features.serverCatalog` → `mode=server`, `shown` совпадает с числом плиток.
3. Капучино: лист показывает «Молоко»/«Сироп» из `products[]`; при пустом молоке у размера —
   секция пустая, а не заполненная соседним размером.
4. Подарок 1 ₽ и списание кэшбэка/ваучера — без изменений.
5. Нет сети → витрина из кэша, режим тот же, что до перезапуска.
