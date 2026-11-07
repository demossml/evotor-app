# DEPLOY — APK choice-scheme 159

## Было → стало
| | Было | Стало |
|---|---|---|
| versionCode | 158 | **159** |
| versionName | `5.2.32-milk-validator` | **`5.3.0-choice-scheme`** |
| HEAD | `249f0c3` | `249f0c3` + накатанный архив (коммит ниже) |

Источник: `evotor-app-choice-scheme-READY.zip` → `/tmp/ev-choice/evotor-app-main`,
накатан rsync (`--delete`, exclude: `.git`, `local.properties`, `*.jks`, `*.keystore`, `app/build`, `.gradle`, `.idea`, `2n.md`).

## Сверка до наката
- изменённые файлы — ровно те, что заявлены в отчёте автора (16 файлов исходников + 1 новый `ChoicePrintStore.kt`);
- **прежние фиксы на месте:** `OptionPicker` VERTICAL (строка 33), debug-панель `ConfigSheetDebug`/`sheetPickerDebug` (16 вхождений в `MainActivity`), `VolumeResolver.kt`, `CatalogMode.kt`, `fromMilkScheme` (`OptionPicker:65`, `ReceiptValidator:53`);
- backend-часть (миграции, `choiceScheme.ts`, админка) в архив **не входит** — она живёт в другом репозитории.

## Маркеры 159
| Маркер | Статус |
|---|---|
| `versionCode = 159`, `versionName = "5.3.0-choice-scheme"` | **ok** |
| `data/ChoicePrintStore.kt` | **ok** |
| `SheetSections.CHOICE = "Выбор"`, секция в `render()` (строка 340) | **ok** |
| новые тесты: `ChoiceConfigParseTest` (11), `CartChoiceTest` (7), `ChoicePrintTest` (9) | **ok** |
| фикстура `product-configuration-choice-fixture.json` | **ok** |

## Проверка боевого пути (не только тесты)
| Звено | Где | Статус |
|---|---|---|
| схема выбора берётся для объёма | `MainActivity:1530` `choiceSchemeFor` → `ProductConfigGuard.choiceScheme(cfg, volumeRefOf(p))` | ok |
| провайдер подключён к листу и комбо | `MainActivity:1825`, `2208` `picker.choiceProvider = ::choiceSchemeFor` | ok |
| блокировка «В чек» без выбора | `OptionPicker.blockers()` = `choiceBlockers()` + правила опций (`:271`, `:282`) | ok |
| повторная проверка по факту нажатия | `MainActivity:1948` | ok |
| выбор записывается в строку корзины | `MainActivity:1971`, `2326` (`choiceTitle`, `choiceLabel`) | ok |
| страховка на оплате | `ReceiptValidator:50` `choice_missing` | ok |
| печать «ЗАКАЗ № → выбор» | `SellLauncher:172` `ChoicePrintStore.save`, `ReceiptPrintExtraService:66-81` | ok |
| выбор **не** позиция чека и **не** влияет на цену | `DrinkConfig` не знает про choice; `unitPrice()` без choice | ok |
| ключ строки корзины включает выбор | `Cart` поля `choiceLabel/choiceTitle` + signature | ok |

## Тесты
```
./gradlew :app:testDebugUnitTest → BUILD SUCCESSFUL
```
- **518 pass / 0 fail / 0 errors**, 60 классов (было 489; +27 новых и +2 в `SheetSectionsTest`);
- `ChoiceConfigParseTest` 11/0, `CartChoiceTest` 7/0, `ChoicePrintTest` 9/0, `SheetSectionsTest` 7/0.

## Сборка и APK
```
./gradlew :app:lintDebug :app:assembleDebug :app:assembleRelease → BUILD SUCCESSFUL
```
- lint: **0 errors, 101 warning**;
- `aapt2 badging`: `versionCode='159' versionName='5.3.0-choice-scheme'`;
- подпись: `CN=6.7 Coffee, OU=POS, O=6.7 Coffee` (release, не debug);
- APK: `/Users/dmitrijsuvalov/Downloads/evotor-app-5.3.0-choice-scheme-v159.apk`;
- размер: **6 120 394** байт;
- sha256: `cbf5f285419225dc1a0843d25140c51bcdd0225adffb658e44f3609dc3e87f5d`.

## WHAT I DID NOT DO
- backend / админку / миграции — не трогал (их нет в архиве; схему «Корица» надо создать на сервере);
- фискальные файлы (`FiscalLines`, `ReceiptPlan`, `DiscountCalc`), лояльность, подарок, ваучеры, сканер — не менял;
- контракт `schemaVersion` и URL configuration — не менял;
- `local.properties`, keystore, `signing`, `applicationId` — не трогал; `.git` и `2n.md` защищены от `--delete`;
- debug-панель `DEBUG_CONFIG_SHEET` оставлена включённой;
- **не пушил** (в задании команды на push не было).

## Готово к установке на кассу: да, с оговоркой
Проверено: компиляция Gradle, 518 unit-тестов, lint, сборка signed release.
**Не проверено:** реальный терминал (печать строки выбора на ленте, ОФД), и порядок выкладки из отчёта автора:
сначала backend + админка, **потом** APK 159, и только затем создавать/назначать схему «Корица» — иначе старый APK пропустит обязательный выбор.
