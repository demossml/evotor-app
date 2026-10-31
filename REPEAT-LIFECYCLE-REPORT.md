# Отчёт: жизненный цикл повтора и проверка номенклатуры до номера (5.2.20)

Версия: **5.2.20-repeat-lifecycle / versionCode 146** (было 5.2.19 / 145).
База: локальный коммит `1ce635b`. Push, публикация APK, backend и серверный API — **не менялись**.

## 1. Исправленные проблемы

### 1.1. Конфликт диалогов повтора
Было: закрытие окна товара сразу вызывало `askRepeatRemainder`, поэтому при быстром открытии
другой карточки диалог «Повтор не завершён» всплывал поверх неё.

Стало:
- закрытие окна только фиксирует прерывание (`RepeatSession.closedWithoutAdd`) и текст
  уведомления (`RepeatInterrupt.notice`), а проверка показа откладывается на 300 мс
  (`REPEAT_INTERRUPT_DELAY_MS`);
- решение принимает `RepeatInterrupt.shouldShow(pending, sheetOpen, dialogShowing)` — диалог
  не показывается, если открыт лист поверх каталога или диалог уже на экране;
- `catalogSheetOpen` выставляется карточкой товара (`showItemSheet`), комбо-листом
  (`showComboSheet`) и листом клиента (`openClientScreen`); при их закрытии проверка
  планируется заново — диалог появится уже после закрытия чужой карточки;
- двойной вызов обработчика закрытия невозможен: в каждом листе флаг `closedOnce`,
  а `RepeatSession.closedWithoutAdd` игнорирует «не нашу» позицию (покрыто тестом);
- отмена остатка (`cancelRepeat`) очищает очередь, `repeatInterrupt` и **ничего не открывает**;
- кнопка на пустом чеке во время сессии — «Продолжить повтор»; она повторно открывает диалог
  «Продолжить повтор / Отменить повтор» (второй экземпляр не создаётся: `repeatDialog?.isShowing`);
- пустая очередь (последняя позиция не добавлена) — кнопка диалога «Повторить заново»,
  запускает новый план по прошлому чеку.

Сценарии, проверенные по коду: крестик/Back/тап мимо (dismiss листа), открытие другой карточки,
повторное открытие диалога, отмена, ручное добавление другого товара.

### 1.2. Резервирование номера очереди
Было: `requestQueueNumberBlocking()` вызывался до проверки номенклатуры внутри `SellLauncher`,
поэтому номер расходовался даже на заблокированный чек.

Стало (перенос безопасен, контракт оплаты не менялся):
```
sendReceipt:
  ReceiptValidator.validate(...)                     // до всего, как было
  ReceiptPlan.cartUuids(Cart.all())                  // чистый: только реальные uuid, без дублей
    → InventoryApi.getProductByUuid по каждому
  missing.isNotEmpty() → уведомление с uuid + перезагрузка каталога + return  // номер НЕ резервируется
  sendingReceipt = true → CheckoutChoice.saveCombo → requestQueueNumberBlocking()
  → SellLauncher.openSellReceipt(..., terminalItems)  // найденные товары передаются как preflight
```
`SellLauncher` использует `preflight` (нет повторных обращений к номенклатуре) и **всё равно**
выполняет `ReceiptPlan.blockReason` как вторую линию защиты. Повторного использования уже
выданного номера нет: номер запрашивается один раз за попытку, а при `blockedBeforeSend`
хранилище очищается (`QueueNumberStore.clear`).

### 1.3. Все пути к `OpenSellReceiptCommand.process`
- единственный вызов — `SellLauncher.kt:163`; позиции в него попадают только после
  `ReceiptPlan.blockReason(...) == null`;
- единственный вызывающий `SellLauncher.openSellReceipt` — `MainActivity.sendReceipt`
  (после `ReceiptValidator` и предварительной проверки номенклатуры);
- все кнопки оплаты (`Пропустить`, апселл, CTA «В чек», карта) идут через
  `openLoyaltyOrSend` → `sendChosen` → `sendReceipt` (5 мест вызова, один вход);
- `DiscountIntegrationService` только читает позиции открытого чека (`ReceiptApi`) и отдаёт
  скидку на чек — своих позиций не создаёт;
- `ReceiptPrintExtraService` добавляет только печатный текст (номер заказа).
  Обходов не найдено; альтернативных «открыть чек» путей в APK нет.

## 2. Изменённые файлы

| Файл | Что изменено |
|---|---|
| `app/src/main/java/ru/sixthcup/evotor/data/RepeatInterrupt.kt` (новый) | `shouldShow(pending, sheetOpen, dialogShowing)`, `notice(left, name)` |
| `app/src/main/java/ru/sixthcup/evotor/ui/MainActivity.kt` | отложенная проверка диалога, `catalogSheetOpen`, `closedOnce`, `repeatDialog`, кнопка «Продолжить повтор», отмена без авто-открытия, предварительная проверка номенклатуры до `requestQueueNumberBlocking`, передача `terminalItems` в `SellLauncher` |
| `app/src/main/java/ru/sixthcup/evotor/sell/ReceiptPlan.kt` | `cartUuids(lines)`, `missingMessage(missing)`; `blockReason` использует общее сообщение |
| `app/src/main/java/ru/sixthcup/evotor/sell/SellLauncher.kt` | параметр `preflight: Map<String, ProductItem.Product>?`; поиск в терминале только если карта не передана |
| `app/build.gradle.kts` | `versionCode 146`, `versionName 5.2.20-repeat-lifecycle` |
| `docs/DYNAMIC-DRINK-CONFIGURATION.md` | раздел 15: жизненный цикл диалога и порядок «номенклатура → номер → чек» |
| `app/src/test/java/ru/sixthcup/evotor/data/RepeatInterruptTest.kt` (новый) | 5 тестов |
| `app/src/test/java/ru/sixthcup/evotor/data/RepeatSessionTest.kt` | 9 → **11** (после отмены ничего не открывается; повторное закрытие не дублирует прерывание) |
| `app/src/test/java/ru/sixthcup/evotor/sell/ReceiptPlanTest.kt` | 16 → **18** (`cartUuids`, текст с uuid) |

## 3. Тесты (XML-отчёты)

`app/build/test-results/testDebugUnitTest/*.xml`, 49 классов:

- **всего 426 тестов, 0 failures, 0 errors, 0 skipped** (было 417);
- `RepeatInterruptTest` — 5: диалог только когда ничего не открыто; подавление при открытой
  карточке; без дубля при уже показанном диалоге; нет прерывания — нет диалога; текст уведомления;
- `RepeatSessionTest` — 11: старт/остаток, следующая только после добавления, закрытие окна
  не продвигает очередь, продолжение, отмена с подсчётом, **после отмены ничего не открывается**,
  **двойное закрытие не дублирует прерывание**, перезапуск без смешивания, чужой товар, пустой план;
- `ReceiptPlanTest` — 18: позиции чека (бесплатное/платное молоко, добавки, `sendToReceipt=false`,
  количество, цены, отсутствие дублей, синтетика), блокировки при отсутствии основного товара /
  молока / добавки, **`cartUuids` для предпроверки**, **сообщение с uuid**;
- `RepeatOrderTest` — 8 (план восстановления прошлого чека).

## 4. Сборки

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
BUILD SUCCESSFUL in 10s
```

- `:app:lintDebug` — **0 errors, 115 warnings** (`lint-results-debug.txt`;
  XML: `UnusedResources` 54, `SetTextI18n` 31, `IconLauncherShape` 10, `GradleDependency` 5,
  `IconDuplicates` 5, `ExportedService` 2);
- `:app:assembleDebug` — APK собран;
- `:app:assembleRelease` — APK собран, подпись `CN=6.7 Coffee, OU=POS, O=6.7 Coffee`;
- в APK присутствуют `RepeatInterrupt`/`ReceiptPlan` (`classes3.dex`) — артефакт соответствует
  текущим исходникам.

## 5. Артефакты

| APK | Размер | SHA-256 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 7 056 767 | `428f4231fc8754e0d1090f510c269e3234e09c81e1de7706dfa8c7a5d7688ea6` |
| `app/build/outputs/apk/release/app-release.apk` | 5 753 444 | `6bdf0182b6eb1fab26a4f38d372e0a9ce542b648e80582dfacd99c10cb9ef2d4` |

`aapt2 badging`: `versionCode='146' versionName='5.2.20-repeat-lifecycle'`.

## 6. Оставшиеся риски

1. **Терминал не проверялся**: реальный чек, поведение POS, `logcat` — только на кассе.
2. **UI-тестов нет** (нет Robolectric/Espresso): логика вынесена в чистые
   `RepeatInterrupt`/`RepeatSession`/`ReceiptPlan`, но сами клики и показ диалогов тестами
   не покрыты. Задержка 300 мс подобрана по опыту, не измерялась на терминале.
3. **Двойное обращение к номенклатуре** осталось в редком случае: если `preflight` не передан
   (другие вызывающие), `SellLauncher` ищет товар сам.
4. **Товар мог исчезнуть между предпроверкой и отправкой** (TOCTOU): эту дырку закрывает
   вторая линия — `ReceiptPlan.blockReason` внутри `SellLauncher`; частичной продажи нет.
5. **Серверный счётчик номеров**: при блокировке номер больше не запрашивается, но если запрос
   уже ушёл и ответ пришёл после блокировки, номер останется в `QueueNumberStore` до следующей
   попытки (локально очищается при `blockedBeforeSend`). Серверный API не менялся.
6. **Backend-маршрут конфигурации** по-прежнему не проверялся (404 → окно по каталогу).
7. Изменения **не отправлены** в GitHub, APK **не опубликован**.
