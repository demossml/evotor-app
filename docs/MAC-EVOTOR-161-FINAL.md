# EVOTOR 161 — финальный отчёт

## Stage 0 Report — инспекция
### Status: GREEN
Точки интеграции зафиксированы в `docs/161-INTEGRATION-MAP.md`:
добавление строки — `ui/MainActivity.kt:1506` / `Cart.add` (`data/Cart.kt:146`);
успешное завершение продажи — `ui/MainActivity.kt:5462` (`onResume`, ветка `sellOpen == false`);
заглушка pull — `ui/orders/OrderDetailActivity.kt:163`.
versionCode на входе: **160**.

## Stage 1 Report — сессия предзаказа
### Status: GREEN
`data/PreorderPullSession.kt`: `set(orderId, shortCode, items)`, `orderId()`, `shortCode()`, `items()`,
`active()`, `clear()`, `attach(context)` (SharedPreferences `preorder_pull`, переживает перезапуск процесса).
Подключена в `SixthCupApp.onCreate` рядом с `Cart.attach`.

**Решение (отличие от буквы промпта):** сессия заполняется **в момент успешного pull**, а не при
resolve. Иначе любой последующий обычный чек помечал бы просмотренный, но не проданный предзаказ
оплаченным. `pull_order_id` из 160 больше не читается.

## Stage 2 Report — реальный «Подтянуть в чек»
### Status: GREEN
`OrderDetailActivity.onPull()` → фон: `InventoryRepository.load(this)` → `PreorderPull.plan(...)` →
`Cart.add(CartLine(...))` (тот же путь, что ручное добавление; `cartKey = pre:<orderId>:<lineId>`).
Успех: сессия + тост `PreorderPull.notice(plan)` + `finish()` (кассир сразу видит корзину).
Ноль позиций: тост с причинами, статус заказа не меняется, кнопка снова активна.
Модификаторы: текст в `recipe` (как `modsText()`), только `sendToReceipt == true`;
фискальных позиций из них не создаём — uuid у них нет.

## Stage 3 Report — markPaid
### Status: GREEN
Единственная точка: `ui/MainActivity.kt:5488` → `markPreorderPaid(orderId)` (`:4105`),
вызывается только при: (а) чек закрыт (`sellOpen == false`), (б) `PreorderPullSession.orderId()` не пуст,
(в) в корзине есть строки этого заказа (`cartKey.startsWith("pre:<orderId>:")`).
Политика ошибки: `Log.w("preorder_paid_failed …")`, чек не откатывается, сессия сохраняется
для возможного повтора. Повторных вызовов на partial-callback нет: ветка срабатывает один раз
(после неё `PendingPayment.clear`).

## Stage 4 Report — версия, тесты, APK
### Status: GREEN
- versionCode **161**, versionName **5.3.2-preorder-pull**
- тесты: **534 / 0 fail / 0 errors**, 63 класса (было 522; +9 `PreorderPullTest`, +3 `PreorderPullSessionTest`)
- `OrderScanRouterTest` — GREEN (loyalty не перехватывается)
- lint: **0 errors, 109 warnings**
- `assembleDebug` / `assembleRelease` — OK
- APK: `dist/sixthcup-evotor-5.3.2-preorder-pull-vc161.apk`, 6 141 948 байт,
  sha256 `5199e7ffdc2d9b9d7193e67b6a7bfc8434849a40bb416df0a27376869cb46506`

## Stage 5 Report — commit / push
### Status: GREEN
Коммит в ветке `feature/preorder-161-pull`, APK в git не попадает (`dist/` в `.gitignore`).

## Non-goals (не делалось)
KDS, редактирование состава после QR, оплата в PWA, редизайн кассы, обход фискального пути.

```
ALL STAGES COMPLETE — EVOTOR 161
APK_READY: /Users/dmitrijsuvalov/Documents/evotor-app/dist/sixthcup-evotor-5.3.2-preorder-pull-vc161.apk
VERSION_CODE: 161
VERSION_NAME: 5.3.2-preorder-pull
```
