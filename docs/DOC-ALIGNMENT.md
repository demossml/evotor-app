# Сверка с документацией Эвотор

| Функция | Документация | Статус |
|---------|--------------|--------|
| Плитка MAIN+EVOTOR | doc_java_app_manifest / doc_java_app_icon | OK |
| GRANTS CASH_OPERATIONS_SELL | doc_app_grants.html | OK |
| OpenSellReceiptCommand + PositionAdd | doc_java_receipt_creation.html | OK (было неверное имя OpenReceiptCommand) |
| NavigationApi.createIntentForSellReceiptPayment | та же дока | OK |
| Position.Builder.newInstance(...) | doc_java_receipt_interactions.html | OK |
| ReceiptDiscountEvent + SetExtra | doc_java_discounts.html | OK |
| InventoryApi / номенклатура | doc_java_inventory.html | OK, только по кнопке меню |

Исправление crash: inventory не в onCreate.
