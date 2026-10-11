# 6.7 Coffee — кассовый клиент Эвотор (4.3.0-cashier-ux / versionCode 70)

## Поток
1. Карта: QR (токен резолвится на backend) или короткий номер; можно продолжить без карты.
2. Заказ: каталог терминала (`InventoryApi` / ProductTable), поиск, плитки; липкая нижняя панель с суммой и «К оплате»; корзина — шторка.
3. «К оплате»: если у карты есть подарок/кэшбэк — одно окно с выбором и итогом; иначе сразу оплата.
4. Чек открывается через `OpenSellReceiptCommand` + `NavigationApi.createIntentForSellReceiptPayment()`; скидка и `extras.sc` — через `DiscountIntegrationService`.
5. После возврата — экран ожидания: «Чек пробит — следующий гость» / «Не оплачено — вернуться к заказу».
6. Backend обрабатывает SELL по poll и начисляет бонусы. Loyalty-запросы идут через HTTPS-прокси Эвотора (секретов в APK нет).

Подробности и список изменений: `CHANGELOG-CASHIER-UX.md`.

## Сборка
`./gradlew :app:assembleDebug -PAPI_BASE_URL=https://app.67coffee.ru`
Единицы цены: `-PPRICE_UNITS=KOPECKS|AUTO|RUBLES` (по умолчанию KOPECKS — так делает integration-library). Правило подарка — `FreeCupRules` (см. CHANGELOG-CASHIER-UX-v2.md).
Тесты: `./gradlew :app:testDebugUnitTest`.

## Зависимости от админки
Без push товаров на точку меню на кассе пустое.
