# IMPLEMENTATION — OptionPicker VERTICAL + debug order

## Baseline
- version до: `versionCode = 156`, `versionName = "5.2.30-sheet-sections-fix"`
- HEAD/commit до: `7c5c09e` (main, синхронизирован с origin)
- подтверждено STEP 0:
  - `class OptionPicker(context: Context) : LinearLayout(context)` — строка 26, **orientation на корне отсутствовал**;
  - `refreshDebugPanel()` — строка 1813 **до** `picker.bind` (1817) и `sheetPickerDebug` (1821).

## Root cause (одной фразой)
`OptionPicker` — `LinearLayout` без `orientation`, т.е. по умолчанию HORIZONTAL: секции «Объём/Молоко/Сироп/…» рисовались в ряд и уезжали вправо, оставаясь ненулевыми в `opts` (строка 6 = 2/7/10, а на экране только объём).

## Diff summary (только файлы этого фикса)
| Файл | Что сделано |
|------|-------------|
| `ui/OptionPicker.kt` | в класс добавлен `init { orientation = LinearLayout.VERTICAL }` (строка 31). Вложенные `header`/`grid`-строки остались HORIZONTAL (340, 363, 410), `textCol` — VERTICAL (430) |
| `ui/MainActivity.kt` | порядок в `showItemSheet`: убран ранний `refreshDebugPanel()` до `bind`; `refresh` вызывается после присвоения новой лямбды; в `rebuildItemSheet` добавлено `sheetPickerDebug = null` до `dismiss`; в колбэке `openProduct` убран ранний `refreshDebugPanel()` (он читал picker прошлого листа) |
| tests | новых зависимостей нет (Robolectric в проекте отсутствует); регресс покрыт существующим `SheetSectionsTest` (прод-набор 3/2/7/0/10 → `[Объём, Молоко, Сироп, Добавки]`) и `ConfigSheetDebugTest` |
| `app/build.gradle.kts` | `versionCode = 157`, `versionName = "5.2.31-picker-vertical"` |

## Порядок в showItemSheet после фикса (номера строк)
1. `picker.bind(variants, opts, start)` — строка **1819**
2. `sheetPickerDebug = { … picker.currentOpts … }` — строка **1823**
3. `refreshDebugPanel()` — строка **1831**

## rebuildItemSheet
- `sheetPickerDebug = null` — строка **1558** (до `dialog.dismiss()`)

Дополнительно: `refresh` в колбэке `openProduct` удалён — обновление панели происходит только внутри листа, после `bind` и новой лямбды.

## Проверки
- `testDebugUnitTest`: **481 pass / 0 fail** (56 классов)
- `assembleDebug`: OK, `assembleRelease`: OK (подпись `CN=6.7 Coffee`)
- `aapt2 badging`: `versionCode='157' versionName='5.2.31-picker-vertical'`
- APK: `/Users/dmitrijsuvalov/Downloads/evotor-app-5.2.31-picker-vertical-v157.apk`
  размер 6 112 987, sha256 `3dc577a88eb969da356abf77f08e3312f6782ce19fa588594512578a08199a14`
- `grep -n "orientation = VERTICAL"` в `OptionPicker.kt`: строка 31 — на корневом классе (`init`)

## Self-check
- OptionPicker orientation = VERTICAL на корневом layout — **YES** (`init`, строка 31)
- Вложенные header/grid rows по-прежнему HORIZONTAL — **YES** (340/363/410)
- `sheetPickerDebug` присваивается после `picker.bind` — **YES** (1823 > 1819)
- После присвоения вызывается `refreshDebugPanel()` — **YES** (1831)
- `rebuildItemSheet` обнуляет `sheetPickerDebug` — **YES** (1558)
- versionCode поднят — **YES** (156 → 157)
- `testDebugUnitTest` без fail — **YES** (481/0)
- Backend не изменён — **YES**
- `DEBUG_CONFIG_SHEET == true` — **YES** (`ConfigSheetDebug.kt:14`)

## WHAT I DID NOT DO
- backend / sixth-cup / PWA / admin — не трогал;
- `ProductConfigApi` parse и URL configuration — не менял;
- `SheetToppings`, merge, loyalty, scanner, `SellLauncher`, `Fiscal*` — не менял;
- debug-панель не удалял, `DEBUG_CONFIG_SHEET` оставлен `true`;
- новых зависимостей (Robolectric) не подключал;
- push не делал (в этом чате явной команды нет);
- **orientation проверен `grep` + компиляцией**: Robolectric/инструментальных тестов в проекте нет, поэтому «в рантайме LinearLayout.VERTICAL» юнит-тестом не подтверждается.

## Риск / что проверить на кассе
Открыть «Капучино»: под панелью и блоком «ОБЪЁМ» должны идти **МОЛОКО** (2 плитки), затем **СИРОП** и **ДОБАВКИ** одной колонкой (скролл при необходимости).
Строка 8 должна быть согласована со строкой 6: `milk` одинаковое, в `sections` присутствует `Молоко` и `Добавки`.
«В чек» остаётся неактивной, пока обязательное молоко не выбрано (обычное молоко уже предвыбрано по `isDefault`).
