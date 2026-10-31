# Отчёт: дубль заголовка точки и вывод `configErrors` (5.2.24)

Версия: **5.2.24-links-diagnostics / versionCode 150**. База: `6e84656` (5.2.23).

## 1. Что показал лог сервера

```
GET /api/devices/products/5/configuration 200 5ms          ← маршрут задеплоен, работает
[warn] configuration store mismatch query=<UUID> header=<UUID>, <UUID> used=<UUID> echo=<UUID> reason=links
```
Два разных сигнала:

1. **`header=<UUID>, <UUID>`** — касса отправляла `X-Evotor-Store-Uuid` **дважды**
   (`ProductConfigApi.fetch` + `DeviceHeaders.apply`), на провод уходил список из двух значений.
   Это наш баг: сервер видел «два заголовка» и писал mismatch, хотя значения одинаковые.
2. **`reason=links`** — сервер сообщает, что объёмы не привязаны к товарам Эвотор этой точки
   (нет/неполные `product_store_links`). В ответе это приходит как `configErrors`.

## 2. Исправлено

| Что | Как |
|---|---|
| дубль заголовка точки | `DeviceHeaders.apply` больше не перезаписывает уже установленный `X-Evotor-Store-Uuid` (`getRequestProperty(...).isNullOrBlank()`), явное значение запроса сохраняется |
| причина не видна кассиру | `ProductConfig.configErrors` парсится из ответа (`["links"]` и `[{"code":"..."}]`), печатается в подсказке окна: «Сервер вернул конфигурацию с ошибкой: объёмы не привязаны к товарам Эвотор этой точки» |
| код `links` | добавлен в `ConfigDiagnostics.errorText` |

## 3. Изменённые файлы

- `net/DeviceHeaders.kt` — без дубля store-заголовка;
- `net/ProductConfigApi.kt` — `parseConfigErrors(...)`;
- `data/ProductConfig.kt` — поле `configErrors` (по умолчанию пусто);
- `data/ConfigDiagnostics.kt` — `links`, вывод `configErrors` в подсказке;
- `ui/MainActivity.kt` — передача `configErrors` в подсказку;
- `app/build.gradle.kts` — 150 / `5.2.24-links-diagnostics`;
- `docs/DYNAMIC-DRINK-CONFIGURATION.md` — раздел 19;
- тесты: `ConfigDiagnosticsTest` 14 (+2), `ProductConfigStrictFieldsTest` 19 (+1).

## 4. Тесты и сборки

- **450 тестов, 0 падений, 0 ошибок**, 52 класса;
- lint: **0 errors, 115 warnings**;
- `assembleDebug` ✅, `assembleRelease` ✅ (`CN=6.7 Coffee`).

| APK | Размер | SHA-256 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 7 063 595 | `b423e218d2fd497b18688495113634727172d2a156bc425e54180b1d70576cc6` |
| `app/build/outputs/apk/release/app-release.apk` | 5 758 936 | `ba30991d80b137f455751c53cc3a646b16eac747aaaa2ef91d74323113afff95` |

## 5. Что должно измениться на кассе

- в логах сервера пропадёт `header=<UUID>, <UUID>` (точка уходит одним значением);
- если `reason=links` настоящий, кассир увидит в окне товара строку
  «Сервер вернул конфигурацию с ошибкой: объёмы не привязаны к товарам Эвотор этой точки» —
  значит нужно заполнить привязки «объём → товар Эвотор» для этой точки;
- если привязки есть и схемы заведены — появятся блоки «Молоко» и «Добавки».

## 6. Ограничения

- Реальный чек на терминале не проверялся (только сборка и unit-тесты).
- `configErrors` не кэшируются: при работе из кэша подсказка покажет общий текст.
- Причина `reason=links` находится на стороне данных backend/админки; APK её только показывает.
