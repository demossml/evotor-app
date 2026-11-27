# CHANGELOG 5.3.29 — лист товара без «двойного открытия» (versionCode 188)

## Что было

При тапе по товару (например «Кофе») карточка открывалась дважды: сначала пустая/урезанная
(конфигурация ещё грузится), затем — после ответа `ProductConfigApi` — закрывалась и
открывалась заново с полным набором секций. На кассе это выглядело как «дёрганье» и
двойная анимация.

Причина: `rebuildItemSheet()` делал `dismiss()` + `show()` нового `BottomSheetDialog`, а
конфигурация молока/добавок приезжала по сети уже после первого показа.

## Что сделано

1. **Перерисовка внутри открытого листа (in-place).**
   `showItemSheet(p, existing)` умеет переиспользовать уже показанный `BottomSheetDialog`:
   контент подменяется через `dialog.setContentView(...)`, повторный `show()` не вызывается.
   Лог: `sheet_content_swapped product=<uuid>`. Fallback на прежнее поведение — если
   пересборка упала (`sheet_rebuild_inplace_failed`).

2. **Открытие сразу из кэша.**
   В `openProduct()` перед открытием читается `ProductConfigCache.load(...)`:
   если конфигурация свежая, лист сразу рисуется с ней (`config_cache_preloaded`),
   а флаг `config_loading` в диагностике заменяется на `config_cache_hit`.
   Когда сервер вернул ровно ту же конфигурацию, пересборка не выполняется
   (`config_unchanged`) — лишнего мигания нет.

3. **Фоновая предзагрузка конфигураций.**
   После обновления каталога запускается `ProductConfigPrefetcher`:
   берутся только товары с опциями (`supportsMods` / `toppings` / `isCombo`),
   без дублей, не больше 40 за проход, пауза 150 мс между запросами.
   На 404 (`unsupported`) проход останавливается. Ошибки — только в лог
   (`ConfigPrefetch`), на продажу и чеки не влияют.

## Тесты

`ProductConfigPrefetchPlanTest` — дедупликация, отброс `id <= 0`, пропуск закэшированных,
лимит, нулевой лимит.

Итог: **691 тест, 0 падений**; `:app:lintDebug` и `:app:assembleRelease` — успешно.

## Что НЕ трогали

Чеки, печать, `SellLauncher`, `sendReceipt`, `ReceiptPrintExtraService`, `SalePrintStore`,
`DiscountIntegrationService`, `SixthCupApp` — без изменений. Никаких новых печатных групп.

## Артефакт

`dist/sixthcup-evotor-5.3.29-sheet-inplace-vc188.apk`
(versionCode 188, versionName `5.3.29-sheet-inplace`, debug-подпись SHA-256
`3b9c4ed3cc64ae2ff93d8c0053ec34ce47bf2644a6f99edf28736424ba729b18`).
