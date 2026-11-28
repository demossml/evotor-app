# CHANGELOG 5.3.30 — откат 188, versionCode 189

## Причина

Версия 188 (`5.3.29-sheet-inplace`) сломала кассу: перерисовка листа товара
внутри уже открытого `BottomSheetDialog`, открытие из кэша конфигурации и
фоновая предзагрузка конфигураций.

## Что сделано

Откат ровно на шаг назад — к поведению кода версии 187:

- `MainActivity.kt` возвращён к состоянию 187 (`git checkout HEAD~1 -- ...`):
  - убрана перерисовка «на месте» (`showItemSheet(p, existing)`), вернулся прежний
    путь `dismiss()` + `show()`;
  - убрано открытие листа из `ProductConfigCache` (`config_cache_preloaded`,
    `config_cache_hit`, `config_unchanged`);
  - убрана фоновая предзагрузка в `loadProducts`.
- Удалены `data/ProductConfigPrefetch.kt` и `ProductConfigPrefetchPlanTest.kt`.
- Сохранены правки версии 187: дефолты молока (`ToppingRules.kt`,
  `OptionPicker.kt`, `ToppingRulesTest.kt`).
- Восстановлена API-совместимая запись `paidInFlight` (`Collections.newSetFromMap`),
  без неё lint падал на `NewApi` (minSdk 23).

Версия: `versionCode = 189`, `versionName = "5.3.30-rollback-188"`.

## Проверки

- **686 тестов, 0 падений**.
- `:app:lintDebug` — успешно.
- `:app:assembleRelease` — успешно.

## Чеки не тронуты

`SellLauncher`, `sendReceipt`, `ReceiptPrintExtraService`, `SalePrintStore`,
`DiscountIntegrationService`, `SixthCupApp` — без изменений.

## Артефакт

`dist/sixthcup-evotor-5.3.30-rollback188-vc189.apk`
(versionCode 189, versionName `5.3.30-rollback-188`, debug-подпись SHA-256
`3b9c4ed3cc64ae2ff93d8c0053ec34ce47bf2644a6f99edf28736424ba729b18`).
