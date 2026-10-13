# Changelog: Фокус + категории на кассе (v4.6.2 / versionCode 77)

## Проблема
- Блок «Фокус» пустел: метка `#focus` жила только в DESCRIPTION Эвотор; после push из админки меток часто нет.
- Чипы категорий («Напитки», «Холодные»…) пропадали: в ProductTable у товаров не было parent-группы (`groupName = null`), chipsScroll = GONE.

## Решение
### Backend `GET /api/devices/catalog`
- В ответ добавлены `categories: [{id,name,sortOrder}]` и у каждого товара `categoryName`, `featured`.
- `featured` = `#focus` / `#combo` в description 6.7 или « + » в названии.
- Дубли `recipe`/`recipeText`, `price`/`priceRub`, `id`/`productId` для совместимости APK.

### Android CatalogApi
- Merge подставляет `groupName` из `categoryName`, если Evotor-группа пустая.
- `featured` с backend + soft-focus: если никто не помечен — до 6 товаров с фото (иначе freeEligible).
- Парсинг `recipeText`, toppings `price` без `priceRub`.

### MainActivity
- После локальной загрузки Inventory — `applySoftFocus` (офлайн тоже не пустой «Фокус»).

## Что не меняли
- extras.sc, loyalty/resolve, SellLauncher, DiscountIntegrationService, applicationId, app_uuid, API_BASE_URL.
