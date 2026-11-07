# 161 — карта интеграции (реальные file:line)

Снято на ветке `feature/preorder-161-pull` (от 160), база `0b924e8`.

| Что | Файл:строка | Ответственность |
|---|---|---|
| Добавление строки в корзину (ручной путь) | `ui/MainActivity.kt:1506` | `Cart.add(CartLine(uuid, name, priceRub, qty, recipe, freeEligible))` |
| Добавление из листа товара | `ui/MainActivity.kt:1980` | `CartLines.of(...)` → `Cart.add` |
| Корзина (синглтон, SharedPreferences) | `data/Cart.kt:41` (`object Cart`), `:146` (`add`), `:54` (`isSellable`) | все экраны процесса добавляют в один Cart |
| Номенклатура терминала | `data/InventoryRepository.kt:108` (`load(context)`) | источник цен/uuid, как в MainActivity |
| Проверка перед оплатой | `data/ReceiptValidator.kt:24` (`validate(lines, products)`) | удалённый товар / опция без uuid → блок |
| Открытие чека продажи | `sell/SellLauncher.kt:44` (`openSellReceipt`) → `OpenSellReceiptCommand.process` (`:163`) | единственная точка SDK |
| Успешное завершение продажи | `ui/MainActivity.kt:5462` (`confirmPendingOrder()` внутри `onResume`, ветка `sellOpen == false`) | чек закрыт → очередь, корзина очищается |
| Pending-заказ | `ui/MainActivity.kt:4099` (`markPendingOrder`), `PendingPayment` | фиксирует состав до оплаты |
| Заглушка «Подтянуть в чек» (160) | `ui/orders/OrderDetailActivity.kt:163` (`onPull`) | тост + `pull_order_id` в prefs, в корзину ничего не кладёт |
| API предзаказов | `net/OrderApi.kt` (`resolveByOid/ByCode`, `active`, `setStatus`, `reject`, `markPaid`), `Item.evotorProductUuid/quantity/modifiers` | сеть только в фоне |

## Решения для 161
1. **Pull выполняется в `OrderDetailActivity`** через `InventoryRepository.load(context)` + `Cart.add(...)`:
   Cart — синглтон процесса, поэтому строки сразу видны на кассе после `finish()`.
   Цена берётся из номенклатуры терминала (`CatalogProduct.priceRub`), не из `estimateTotal`.
2. **Модификаторы** без `evotorUuid` не превращаются в фискальные позиции: их текст идёт в `recipe`
   строки (как `picker.modsText()` для ручной конфигурации). Позиции с `sendToReceipt=false` не упоминаются в рецепте.
3. **markPaid** — единственная точка `ui/MainActivity.kt:5462` (ветка «чек закрыт»), только если
   `PreorderPullSession.orderId` не пуст; ошибка сети → лог, чек не откатывается.
