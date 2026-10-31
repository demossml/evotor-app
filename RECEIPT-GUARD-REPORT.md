# Отчёт: защита чека без товара в кассе и безопасный повтор (5.2.19)

Версия: **5.2.19-receipt-guard / versionCode 145** (было 5.2.18 / 144).
База: локальные коммиты `549b51c`, `c02d860`. Push, публикация APK и изменения backend — **не выполнялись**.

## 1. Изменённые файлы

| Файл | Что изменено |
|---|---|
| `app/src/main/java/ru/sixthcup/evotor/sell/ReceiptPlan.kt` | позиция создаётся только для товара, найденного в номенклатуре; удалён fallback «ручной» позиции; добавлены `Result.missing` и `blockReason()` |
| `app/src/main/java/ru/sixthcup/evotor/sell/SellLauncher.kt` | проверка `blockReason` до `CheckoutChoice.save` и `OpenSellReceiptCommand.process`; конструктор `Position.Builder.newInstance(uuid, productUuid, name, Measure, …)` больше не вызывается; `Outcome.blockedBeforeSend`; убраны неиспользуемые импорты `Measure`, `UUID` |
| `app/src/main/java/ru/sixthcup/evotor/data/RepeatSession.kt` (новый) | чистый автомат очереди повтора: `start` / `added` / `closedWithoutAdd` / `resume` / `cancel` |
| `app/src/main/java/ru/sixthcup/evotor/ui/MainActivity.kt` | `ArrayDeque` заменён на `RepeatSession`; закрытие окна помечает повтор прерванным и показывает выбор «Продолжить / Отменить»; кнопка «Повторить» → «Отменить повтор»; при `blockedBeforeSend` снимается зарезервированный номер очереди; очередь продвигается до закрытия окна |
| `app/build.gradle.kts` | `versionCode 145`, `versionName 5.2.19-receipt-guard` |
| `docs/DYNAMIC-DRINK-CONFIGURATION.md` | раздел 14: контракт SDK, порядок оплаты, поведение `RepeatSession` |
| `app/src/test/java/ru/sixthcup/evotor/sell/ReceiptPlanTest.kt` | 12 → **16** тестов (нет основного товара / молока / добавки, чек без блокировки) |
| `app/src/test/java/ru/sixthcup/evotor/data/RepeatSessionTest.kt` (новый) | **9** тестов очереди повтора |

## 2. Контракт Evotor SDK (проверено по jar, не предположение)

`integration-library-v0.6.27.aar` → `classes.jar`, `javap -c 'ru.evotor.framework.receipt.Position$Builder'`:

```
newInstance(String uuid, String productUuid, String name, Measure, BigDecimal price, BigDecimal qty)
  → new Position(uuid, productUuid, null /*productCode*/, ProductType.NORMAL, name, measure,
                 null /*TaxNumber*/, price, price, qty, null, null, …)
```

Факты:
- «ручная» позиция получает **жёстко зашитый** `ProductType.NORMAL`;
- `TaxNumber` = `null` (передаётся `aconst_null`);
- в отличие от `newInstance(ProductItem.Product, BigDecimal)`, который берёт НДС/тип/код из номенклатуры.

Вывод: гарантировать корректную фискализацию такой строки нельзя, поэтому fallback удалён —
чек блокируется. Поведение самого POS при `TaxNumber = null` **на терминале не проверялось**.

## 3. Что исправлено

1. **Нет «ручных» позиций.** Если `InventoryApi.getProductByUuid` не вернул товар, строка
   попадает в `ReceiptPlan.Result.missing`, позиция не создаётся.
2. **Чек блокируется до передачи позиций.** `SellLauncher` вызывает
   `ReceiptPlan.blockReason(plan)` сразу после сборки плана:
   `«Нет товара в кассе: <имя> (<uuid>). Обновите каталог Эвотор.»` — до `CheckoutChoice.save`
   и до `OpenSellReceiptCommand.process`. Частичной продажи нет.
3. **Номер очереди не тратится.** При `Outcome.blockedBeforeSend` касса очищает
   `QueueNumberStore`/`SalePrintStore` (лог `receipt_blocked_before_send number_cleared`),
   заказ не помечается как PENDING.
4. **Повтор не теряется молча.** Закрытие окна повтора без добавления:
   `RepeatSession.closedWithoutAdd` → уведомление «Повтор не завершён: осталось N поз.» +
   нижний лист с кнопками **«Продолжить повтор»** / **«Отменить повтор»**.
5. **Ошибка конфигурации не продвигает очередь.** Следующая позиция открывается только из
   `RepeatSession.added(...)`, то есть после фактического `Cart.add`.
6. **Новая очередь не смешивается со старой.** `RepeatSession.start()` очищает предыдущую
   (в том числе `pending`, `completed`, `incomplete`).
7. **Ручное добавление другого товара** очередь не трогает: `added`/`closedWithoutAdd`
   сравнивают uuid и возвращают «не наша позиция».
8. **Явная отмена остатка.** Кнопка на пустом чеке во время повтора — «Отменить повтор»
   (`RepeatSession.cancel()` → «Повтор отменён: не добавлено N поз.»).

## 4. Реальный путь оплаты (проверено по коду)

```
«В чек» (openLoyaltyOrSend)
  → sendChosen → sendReceipt
      → guard sendingReceipt + Cart.isEmpty
      → ReceiptValidator.validate(Cart.all(), products)      // удалённый товар, опция без uuid
          issues.isNotEmpty() → showReceiptIssues() и return // до SDK
      → requestQueueNumberBlocking()
      → SellLauncher.openSellReceipt
          → FiscalLines.build
          → ReceiptPlan.plan(fiscalLines, InventoryApi)      // missing → чек не открываем
          → ReceiptPlan.blockReason(plan) != null → Outcome(blockedBeforeSend = true), return
          → CheckoutChoice.save / OpenSellReceiptCommand(...).process   // единственная точка отправки
```

Единственный вызов SDK — `SellLauncher.kt:163`; до него стоят обе проверки.

## 5. Тесты (XML-отчёты, не только BUILD SUCCESSFUL)

`app/build/test-results/testDebugUnitTest/*.xml` (48 классов):

- **всего: 417 тестов, 0 failures, 0 errors, 0 skipped**;
- `ReceiptPlanTest` — **16**, 0 падений:
  основной товар одной позицией; бесплатное молоко вне чека; платное молоко отдельной позицией
  (uuid + цена); добавка; несколько добавок; `sendToReceipt=false`; количество; цена из терминала;
  скидочная цена только когда ниже; синтетика отброшена; полный путь «конфигурация → корзина →
  позиции»; полный путь с бесплатным молоком; **нет основного товара → блок с uuid**;
  **нет платного молока → блок**; **нет добавки → блок**; обычный чек не блокируется;
- `RepeatSessionTest` — **9**, 0 падений: старт/остаток; следующая только после добавления;
  закрытие окна не продвигает очередь; продолжение; отмена с подсчётом потерь; перезапуск без
  смешивания; ручное добавление чужого товара; закрытие чужого окна; пустой план;
- `RepeatOrderTest` — 8 (план восстановления прошлого чека), 0 падений.

## 6. Сборки (фактические результаты)

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
BUILD SUCCESSFUL in 10s
```

- `:app:lintDebug` — **0 errors, 115 warnings** (`lint-results-debug.txt`, XML:
  `UnusedResources` 54, `SetTextI18n` 31, `IconLauncherShape` 10, `GradleDependency` 5,
  `IconDuplicates` 5, `ExportedService` 2);
- `:app:assembleDebug` — APK собран;
- `:app:assembleRelease` — APK собран, подпись `CN=6.7 Coffee, OU=POS, O=6.7 Coffee` (release-ключ);
- содержимое APK: `ReceiptPlan`/`RepeatSession` найдены в `classes3.dex` — артефакт соответствует
  текущим исходникам.

## 7. Артефакты

| APK | Размер | SHA-256 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 7 053 191 | `5c93abd64a73faee30b67f89cc3ae45de62460158aeca0141808e554bf9047eb` |
| `app/build/outputs/apk/release/app-release.apk` | 5 750 884 | `b689692beb4b7470991f97cf4d837f67483c11ec3833b1091a4f4bc574fc6f64` |

`aapt2 badging`: `versionCode='145' versionName='5.2.19-receipt-guard'`.

## 8. Оставшиеся ограничения

1. **Терминал Эвотор не проверялся.** Поведение POS при `TaxNumber = null` (почему fallback и
   убран), реальный чек с платным молоком/добавками, `logcat -s SellLauncher SC_TOP` — только
   на кассе. Официальная документация Evotor по ручным позициям в этой сессии не сверялась,
   выводы сделаны из байткода SDK v0.6.27.
2. **UI-тестов нет** (нет Robolectric/Espresso): проверены чистые `RepeatSession`, `RepeatOrder`,
   `CartLines`, `ReceiptPlan`, но не клики «В чек»/«Повторить» и не показ нижнего листа
   «Продолжить / Отменить».
3. **Диалог прерывания** появляется и тогда, когда кассир сразу открыл другую карточку
   (предыдущее окно закрылось) — уведомление корректное, но лист может перекрыть новую карточку.
4. **Backend-маршрут конфигурации** не проверялся (404 → окно по каталогу).
5. **Серверный номер очереди** запрашивается до `SellLauncher`; при блокировке он снимается
   локально, но счётчик на сервере уже израсходован (дыра в серверной нумерации возможна).
6. Изменения **не отправлены** в GitHub, APK **не опубликован**, backend не менялся.
