# 4.9.14-catalog-groups (versionCode 113)

## Зачем
Backend migration 027: добавки = обычные products с `parent_product_id` на группу (`is_group=1`: Молоко / Сиропы / Сахар / Добавки). В Эвотор уходит `parent_id` + uuid товара-прокси.

## Что изменилось в APK
- `CatalogApi.Enrich`: поля `isGroup`, `parentProductId`.
- Парсинг `/api/devices/catalog`: `hideFromMenu = hideFromMenu || isModifier || isGroup`.
- Merge с номенклатурой терминала: группы не становятся плитками.
- `CatalogCache`: сохраняет/читает `isGroup` и `parentProductId`.
- `InventoryRepository` уже пропускал `IS_GROUP` из ProductTable — без изменений.

## Контракт backend (нужно отдавать в catalog)
В каждом элементе `products[]`:
- `isGroup` (bool) — `products.is_group == 1`
- `isModifier` / `hideFromMenu` — как раньше для linked modifier products
- `parentProductId` (int|null) — опционально, для отладки
- toppings[].`evotorUuid` — uuid товара-добавки в `product_store_links` (без name-match)

Группы (`is_group=1`) на витрине кассы не показываются. Платные допы без `evotorUuid` по-прежнему unavailable.

## Совместимость
Старый backend без `isGroup` работает: поле default false, логика isModifier/hideFromMenu прежняя.
