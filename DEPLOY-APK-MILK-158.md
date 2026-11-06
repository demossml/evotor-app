# DEPLOY — APK milk-validator 158

## Было → стало
| | Было | Стало |
|---|---|---|
| versionCode | 157 | **158** |
| versionName | `5.2.31-picker-vertical` | **`5.2.32-milk-validator`** |
| HEAD | `74daea4` | `74daea4` + незакоммиченные изменения архива (не коммитил — см. ниже) |

Источник: `evotor-app-milk-validator-READY.zip` → `/tmp/ev-milk/evotor-app-main`, накатан rsync-ом
(exclude: `.git`, `local.properties`, `*.jks`, `*.keystore`, `app/build`, `.gradle`, `.idea`, `2n.md`).

**Архив аддитивный:** по одному изменению в 7 файлах + новый тест. Проверено `diff` до наката:
- `MainActivity.kt` — ровно 1 добавленная строка (`fromMilkScheme = o.key.startsWith("c:m:")`);
- `OptionPicker.kt` — ровно 1 строка (`fromMilkScheme = key.startsWith("c:m:")`);
- `Cart.kt`, `CartLines.kt`, `DrinkConfig.kt`, `InventoryRepository.kt`, `ReceiptValidator.kt` — протяжка флага;
- прежние фиксы **на месте**: `OptionPicker` VERTICAL, `ConfigSheetDebug`, `VolumeResolver`, `CatalogMode`, диагностика `SC_SHEET`.

## Маркеры
| Маркер | Статус |
|---|---|
| `versionCode = 158`, `versionName = "5.2.32-milk-validator"` | **ok** (`app/build.gradle.kts:12-13`) |
| `ReceiptValidator`: `if (line.fromMilkScheme) continue` | **ok** (`data/ReceiptValidator.kt:41`) |
| `OptionPicker`: `fromMilkScheme = key.startsWith("c:m:")` | **ok** (`ui/OptionPicker.kt:63`) |
| `MilkLineFlagTest.kt` | **ok** (`app/src/test/java/ru/sixthcup/evotor/data/MilkLineFlagTest.kt`) |

## Тесты
```
./gradlew :app:testDebugUnitTest
BUILD SUCCESSFUL
```
- **489 pass / 0 fail / 0 errors**, 57 классов (было 481);
- `MilkLineFlagTest` — 4 теста, 0 падений;
- `ReceiptValidatorTest` обновлён архивом и проходит.

## Сборка и APK
```
./gradlew :app:assembleDebug :app:assembleRelease → BUILD SUCCESSFUL
```
- `aapt2 badging`: `versionCode='158' versionName='5.2.32-milk-validator'`
- подпись: `CN=6.7 Coffee, OU=POS, O=6.7 Coffee` (release-ключ, не debug)
- APK: `/Users/dmitrijsuvalov/Downloads/evotor-app-5.2.32-milk-validator-v158.apk`
- размер: **6 113 501** байт
- sha256: `411533efb04857bcb71f521537e73ec9f8d9efbef43c182b5b79bed818c0252f`

## WHAT I DID NOT DO
- backend, sixth-cup, PWA, admin — не трогал;
- данные молока/схемы не «чинил»;
- `ProductConfigApi`, URL configuration, merge, `SheetToppings`, loyalty, scanner, `SellLauncher`,
  `Fiscal*`, signing, `applicationId`, `local.properties`, keystore — не менял;
- debug-панель не удалял (`DEBUG_CONFIG_SHEET = true`);
- **не коммитил и не пушил** (в задании явной команды на push нет; локальный коммит — отдельным шагом по команде владельца).

## Готово к установке на кассу: **да**
Оговорка: проверено unit-тестами и сборкой; реальный чек на терминале не прогонялся.
Ожидаемый эффект: позиция платного молока из схемы молока больше не блокируется
`ReceiptValidator` (раньше сверялась с добавками каталога — разные пространства id),
поэтому молоко уходит в чек вместе с напитком.
