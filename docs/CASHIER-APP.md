# 6.7 Coffee — кассовый клиент Эвотор (v3.0 / versionCode 54)

## Поток
1. Карта: QR (скидка из token) или короткий номер (только привязка в extras).
2. Меню: `InventoryApi` / ProductTable терминала (после admin → Cloud → sync терминала).
3. Корзина в нашем UI.
4. «Пробить» → `OpenReceiptCommand` + позиции; скидка/extras через `DiscountIntegrationService`.
5. Backend poll SELL → начисления. **Нет** HTTPS с кассы на app.67coffee.ru.

## Сборка
Подставить `SERVER_PUBLIC_KEY` и `SERVER_KEY_ID` в `gradle.properties` (как на backend).
`./gradlew :app:assembleDebug`

## Зависимости от админки
Без push товаров на точку меню на кассе пустое.
