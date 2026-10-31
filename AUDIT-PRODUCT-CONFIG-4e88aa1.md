# АУДИТ — Product Configuration (коммит 4e88aa1, версия 142)

Дата: 31.10.2026 · Аудит проводился по фактическому коду, не по комментариям.
Новые версии APK не выпускались, релиз не публиковался.

## Итог одной таблицей

| № | Вопрос | Вердикт | Действие |
|---|---|---|---|
| 1 | Товар с обязательным молоком может уйти в чек без выбора | **Дефект (был)** | исправлен |
| 2 | Сетевая ошибка → продажа по старой логике | **Дефект (был)** | исправлен |
| 3 | Кэш проверяется по storeUuid/productId/актуальности | Частично: storeUuid и productId проверяются, срока годности нет | оставлено, показан возраст |
| 4 | Схема определяется для каждого объёма | **Дефект (был)**: fallback на `cfg.productId` | исправлен |
| 5 | Платное молоко может попасть в чек дважды | Нет | — |
| 6 | Обычное молоко может попасть в чек | Нет | — |
| 7 | `sendToReceipt` проверяется в механизме чека | Да | — |
| 8 | Цена молока/добавок из номенклатуры | Да, с fallback для недоступных | — |
| 9 | Пустая схема / битый JSON / чужой товар | Пустая и битый — обработаны; **чужой товар принимался** | исправлен |
| 10 | Быстрое открытие двух карточек | Устаревший ответ отбрасывается | — |

---

## Q1. Товар с обязательной схемой молока без выбора

**Цепочка (до фикса):** `onProductTap` → `openProduct` (MainActivity:1101) → `backendId == null`
→ `openProductLegacy` (1152) → `variantModel.hasChoice(p) == false` → `Cart.add(...)` (1157).

**Подтверждение:**
```kotlin
// MainActivity.kt:1106-1109 (до фикса)
if (backendId == null || storeUuid.isNullOrBlank()) {
    openProductLegacy(p, "config_skipped reason=no_product_id")
    return
}
// MainActivity.kt:1155-1159 (до фикса)
if (variantModel.hasChoice(p) || pendingConfigFor(p) != null) showItemSheet(p)
else Cart.add(CartLine(p.uuid, p.name, ...))
```
Если backend знает про товар обязательную схему молока, а касса не смогла сопоставить `productId`
(или в каталоге нет toppings), товар добавлялся в чек без окна — обязательное молоко обходилось.

**Тест-воспроизведение:** `ProductConfigPolicyTest` (5 тестов) — фиксирует, что право на «молчаливое»
добавление есть только при явном подтверждении сервера. До фикса класса не существовало, тест не компилировался.

**Исправление:** `MainActivity.kt:1108, 1143, 1153` — во всех неопределённых случаях
`allowDirectAdd = false`; `openProductLegacy` (1167-1173) открывает окно, если права на прямое
добавление нет. Право даёт только `ProductConfigPolicy.silentAddAllowed(result)`
(`ProductConfigApi.kt`, политика) = `config != null && noConfiguration`.

## Q2. Сетевая ошибка → старая логика

**Было:** ветка `else` (MainActivity:1149-1154) при ошибке без кэша вызывала
`openProductLegacy(p, "config_fallback_catalog")` → для товара без выбора в каталоге — прямой `Cart.add`.

**Тест:** `ProductConfigPolicyTest.network error does not allow silent add` (timeout, null-результат),
`unsupported route does not allow silent add` (404), `forbidden or server error` (403/500) — все требуют `false`.

**Исправление:** `MainActivity.kt:1153` — `allowDirectAdd = false`. При ошибке кассир видит окно
товара (с данными каталога) и предупреждение; прямой продажи «вслепую» больше нет.

## Q3. Кэш: проверки и актуальность

`ProductConfigCache.load` (`ProductConfigCache.kt:33-41`) читает файл `product_config_<store>_<pid>.json`
и прогоняет содержимое **через тот же парсер**, что и сеть: `ProductConfigApi.parse(..., productId, storeUuid)`
— значит проверяются и `storeUuid`, и `productId` (после фикса Q9), и `schemaVersion`.
Ключ файла включает store — данные разных точек не смешиваются (`file()`, 20-22).

Актуальность: срок годности не задан; `savedAt` возвращается и используется — кассир видит плашку
«используются сохранённые данные», в лог уходит `config_cache_used … ageMs=…` (MainActivity:1136-1140).
Ограничение зафиксировано ниже.

## Q4. Схема для каждого объёма

**Было (дефект):** `optionsFor` (MainActivity:1195-1200 до фикса):
```kotlin
val volumeId = cfg.volumes.firstOrNull { it.evotorUuid == p.uuid }?.productId
    ?: … ?: cfg.productId     // ← схема запрошенного товара подставлялась чужому объёму
```
При смене объёма на размер, которого нет в `volumes[]` (или с другим uuid), касса показывала
схему молока другого размера.

**Тест:** `ProductConfigCacheTest.audit Q4 - milk scheme of another volume is not used`:
`milkForVolume("VOL-250")` → схема; `milkForVolume("VOL-350")` → `null`; неизвестный uuid → `null`.

**Исправление:** `ProductConfig.kt` — `milkForVolume`/`additionsForVolume` (строгое совпадение по
Evotor uuid, иначе `null`); `MainActivity.kt:1195-1198` — fallback на `cfg.productId` убран,
при неизвестном объёме работают опции каталога.

## Q5. Платное молоко дважды

`DrinkConfig.goesToReceipt` (DrinkConfig.kt:38-43) и дедупликация в `receiptLines`
(`seen.add(variant.uuid)` :55, `if (!seen.add(uuid)) continue` :60). Плюс `optionsFor` при наличии
конфигурации возвращает **только** опции конфигурации (MainActivity:1199), каталог и конфигурация
не смешиваются. Тесты: `DrinkConfigTest.G - same product is not charged twice`,
`G - option with the volume uuid is not duplicated`.

## Q6. Обычное молоко в чеке

`configOpts` (MainActivity:1211-1224) ставит `zeroOption = o.isFree` и `sendToReceipt = o.sendToReceipt`;
`Opt.toTopping()` (OptionPicker) переносит оба флага; `DrinkConfig.goesToReceipt` (DrinkConfig.kt:40)
исключает `zeroOption` и `sendToReceipt = false`. Даже если сервер ошибочно пришлёт uuid обычному
молоку — исключение по флагам. Тесты: `6 - ordinary milk is free and never a receipt line`,
`config milk - ordinary milk is not a receipt line even with uuid`,
`A - ordinary milk adds no money`.

## Q7. `sendToReceipt` в фактическом механизме чека

Кнопка «В чек» листа вызывает `DrinkConfig.receiptLines(variant, picker.chosenToppings())`
(MainActivity:1493-1496) → `goesToReceipt` (DrinkConfig.kt:40) → `Cart.add` (:1499-1509) →
`SellLauncher` → `OpenSellReceiptCommand`. Второго пути сборки позиций нет.
Тесты: `G - server may forbid sending an option to the receipt`,
`config additions - paid additions are lines, zero options are not`.

## Q8. Цена молока и добавок

`configOpts.terminalPrice` (MainActivity:1209-1210):
```kotlin
uuid?.let { u -> products.firstOrNull { it.uuid == u }?.priceRub } ?: fallback
```
`products` — результат `InventoryRepository.load` (цена `PRICE_OUT` терминала), merge её не меняет.
Если платной опции нет в номенклатуре — `available = false` (недоступна), цена сервера не применяется.
Для базового объёма цена берётся из `variant.priceRub` (та же номенклатура). `SellLauncher` и на
отправке сверяет цену с терминалом (`price_mismatch` в лог).

## Q9. Пустая схема, битый JSON, чужой товар

- Пустая схема (`options: []`) → `parseMilk`/`parseAdditions` возвращают `null`
  (`ProductConfigApi.kt:166-168, 205-208`), блок не рисуется. Тест:
  `empty option arrays do not create fake schemes`.
- Битый JSON / `{}` → `FetchResult(null, …, error)`, приложение не падает
  (тесты `15 - broken json does not crash`, `15 - empty json object…`).
- **Чужой товар (дефект):** `productId` из ответа не сверялся с запрошенным — принималась
  конфигурация другого SKU. **Тест:** `audit Q9 - response for another product is rejected`.
  **Исправление:** `ProductConfigApi.kt:121-125` — при несовпадении
  `FetchResult(null, …, "Конфигурация другого товара")`.

## Q10. Быстрое открытие двух карточек

`openProduct`: повторный тап по той же карточке отсекается (`configLoadingUuid == p.uuid`, :1103);
каждый запрос получает поколение `val gen = ++configGen` (:1116), ответ применяется только если
поколение совпадает (:1124) — ответ для первой карточки после открытия второй отбрасывается.
Если второй ответ так и не придёт, `configLoadingUuid` останется занятым, но `fetch` ограничен
таймаутами (5/10 с) и всегда завершается, возвращая результат в UI-поток.

---

## Изменённые файлы (только по подтверждённым дефектам)

| Файл | Что изменено |
|---|---|
| `net/ProductConfigApi.kt` | проверка `productId` ответа (Q9); объект `ProductConfigPolicy` (Q1/Q2) |
| `data/ProductConfig.kt` | `milkForVolume` / `additionsForVolume` — схема строго по объёму (Q4) |
| `ui/MainActivity.kt` | `openProductLegacy(..., allowDirectAdd)`; запрет прямого добавления при неизвестной конфигурации; убран fallback на `cfg.productId` |
| `net/ProductConfigPolicyTest.kt` (новый) | 5 тестов политики |
| `net/ProductConfigParseTest.kt` | +1 тест (чужой товар) |
| `data/ProductConfigCacheTest.kt` | +1 тест (схема по объёму) |

## Тесты

```
:app:testDebugUnitTest   -> 353 pass / 0 fail
  ProductConfigParseTest   16 / 0
  ProductConfigPolicyTest   5 / 0
  ProductConfigCacheTest    3 / 0
  DrinkConfigTest          16 / 0
:app:lintDebug           -> BUILD SUCCESSFUL, 0 errors
:app:assembleDebug       -> BUILD SUCCESSFUL, app-debug.apk 7 035 993 байт, versionCode=142
```
Release APK не пересобирался и не публиковался (по условию задания).

## Оставшиеся ограничения

1. **Реальный чек на терминале не проверялся** — ни до, ни после фикса. Проверка «обычное молоко
   не в чеке, платное — в чеке» подтверждена только unit-тестами моделей.
2. **Backend-маршрута `/api/devices/products/{id}/configuration` пока нет**; до его появления
   касса получает 404 и после фикса открывает окно по данным каталога (прямого добавления нет).
3. **Срока годности кэша нет** — конфигурация может быть старой; кассир видит плашку и в лог
   пишется возраст. Жёсткий TTL не вводил: в ТЗ он не задан.
4. UI-тестов нет (нет Robolectric/Espresso): проверки «какое окно открылось» держатся на чтении
   кода и на чистой политике `ProductConfigPolicy`.
5. Если сервер не пришлёт `productId` в ответе совсем (0), проверка Q9 не срабатывает —
   принимается запрошенный id (такой ответ считается «своим»).
