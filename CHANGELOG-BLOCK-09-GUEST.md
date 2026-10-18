# Block 09 — guest card screen (index → Android)

Источник: index `#guestOverlay` / `.guest-sheet` / `.guest-card` / qr-zone / phone-field.

## Без карты
- Head «Карта гостя · Лояльность 6.7»
- QR-зона: «Поднесите карту гостя»
- Поле номера + → (resolve через LoyaltyApi)
- Последняя карта (если была в сессии)
- «Продолжить без карты»

## С картой
- Brand-панель: 6.7 Club, код, чашки, кэшбэк, progress dots
- «К заказу» / «Отвязать»
- Без выдуманной истории покупок (в APK её нет)

## Не трогали
- LoyaltyApi.resolve / ScannerReceiver / CardSession
