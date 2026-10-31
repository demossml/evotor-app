# Отчёт: откат серверной витрины (5.2.23, versionCode 149)

## 1. Причина регресса — мой промах

В 5.2.22 я добавил отправку `X-App-Version-Code`. Backend включает по нему
`features.serverCatalog=true`, и касса переключилась на **серверную витрину**:

```
val serverMode = enrich.features?.serverCatalog == true
val serverShelf = if (serverMode) ServerCatalog.build(inv, enrich) else null
val merged = serverShelf?.products ?: mergedResult.first
```

`ServerCatalog.build` строит плитки строго из серверного JSON: **без** склейки размеров
по меткам `#group=/#size=` и **без** донора схем. Отсюда:

| Что вы видели | Почему |
|---|---|
| все объёмы капучино отдельными плитками | серверный режим не склеивает размеры в одну карточку |
| пропал блок «Объём» в карточке | плитка = один товар, соседних размеров в карточке нет |
| добавки не появились | в серверном режиме `sheetProduct()` = сам товар, донор схем отключён, а схем в админке пока нет |
| группы товаров исчезли | витрина строится из `ServerCatalog`, а не из склейки по группам |

## 2. Что сделано

`data/CatalogMode.kt`:
```kotlin
const val SERVER_CATALOG_ALLOWED = false
fun useServerCatalog(featureFlag: Boolean?, allowed: Boolean = SERVER_CATALOG_ALLOWED) =
    allowed && featureFlag == true
```
Используется в обоих местах, где раньше читался флаг: свежий каталог (`loadProducts`)
и кэш (`cachedServerMode`). Заголовок `X-App-Version-Code` **оставлен** (backend видит версию
и может готовиться), но витрина снова строится прежней склейкой: группы и объёмы вернулись.

Включение серверного режима обратно — одна константа `SERVER_CATALOG_ALLOWED = true`
после проверки каталога на кассе.

## 3. Тесты и сборки

- тесты: **447 / 0 падений**, 52 класса (в т.ч. новый `CatalogModeTest` — 2 теста:
  в текущей сборке флаг всегда даёт `false`, при `allowed=true` флаг уважается);
- lint: **0 errors, 115 warnings**;
- `assembleDebug` ✅, `assembleRelease` ✅ (`CN=6.7 Coffee`).

| APK | Размер | SHA-256 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 7 062 474 | `f715db34452fb421a546ffeb412e3e7dab21d12cf8c2c1c250952ec31d0919d9` |
| `app/build/outputs/apk/release/app-release.apk` | 5 757 885 | `9525a092c6d8dd3f8ce6a4bed067df660d915ca38ba78c6f417fa7a781fa70ab` |

`versionCode=149`, `5.2.23-catalog-groups-back`.

## 4. Что ожидать на кассе после установки

1. Плитки снова сгруппированы: капучино — одна плитка, объёмы внутри карточки.
2. В карточке снова есть блок «Объём».
3. Добавки появятся, когда в админке будет схема (или когда задеплоят маршрут `/configuration`
   и заполнят `product_store_links`) — APK их покажет, как только данные придут.
4. Кэш каталога перезапишется при первом обновлении (раз в 60 с) или по pull-to-refresh.

## 5. Ограничения

- Серверная витрина отключена принудительно: если backend считает её готовой, её надо проверить
  на тестовой точке и только потом включать константой.
- Реальный чек на терминале не проверялся (только сборка и unit-тесты).
