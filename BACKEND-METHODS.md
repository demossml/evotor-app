# Backend methods for 6.7 Coffee Evotor app

Base: `https://app.67coffee.ru` (или `-PAPI_BASE_URL=...`).
APK ходит только по HTTPS через прокси Эвотора; Authorization инжектит Cloud.

## Уже используются

### GET /api/devices/catalog
- Headers: optional `X-Evotor-Store-Uuid`
- Response JSON:
```json
{
  "products": [{
    "evotorUuid": "...",
    "productId": 123,
    "name": "Капучино",
    "priceRub": 250,
    "imageUrl": "https://...",
    "recipe": "...",
    "description": "...",
    "categoryName": "Кофе",
    "featured": true,
    "countsAsCup": true,
    "freeEligible": true,
    "toppings": [{
      "id": 1, "name": "Ваниль", "priceRub": 40,
      "groupKey": "syrup", "maxCount": 2, "evotorUuid": null
    }],
    "upsell": [{ "productId": 9, "evotorUuid": "...", "name": "Круассан", "priceRub": 180, "imageUrl": "..." }]
  }],
  "categories": [{ "name": "Кофе" }],
  "upsellDefault": [ ... ]
}
```

### POST /api/devices/loyalty/resolve
- Body: `{ "code": "<qr-or-short>" }`
- 200: `{ "ok": true, "cardCode", "paidCups", "cupsForFree", "freeAvailable", "cashback", "reservationId?", "reservationExpiresAt?", "freeStatus" }`
- 400/404: карта невалидна → APK не цепляет к чеку
- Offline: signed QR всё равно уходит в extras (бонусы без сети не применяются)

## Добавить / доработать

### POST /api/devices/loyalty/reserve
Резерв подарка и потолка кэшбэка на время оформления.
```json
// request
{ "code": "...", "storeUuid": "...", "lines": [
  { "productId": 1, "qty": 1, "toppingIds": [2,3] }
]}
// response
{ "ok": true, "reservationId": "uuid", "expiresAt": 1730000000,
  "freeAvailable": 1, "maxCashbackKopecks": 5000 }
```

### POST /api/devices/loyalty/cancel-reserve
```json
{ "reservationId": "uuid" }
```

### SELL handler (Cloud webhook PUT или poll documents)
1. Найти `extras.sc` (или ваш ключ): code / token, reservationId, cashbackKopecks, free applied.
2. Идемпотентно по `documentUuid` / `uuid` документа.
3. Списать reservation, начислить cups, списать/начислить cashback.
4. Не доверять сумме скидки с терминала как источнику истины — пересчитать на сервере.

### Cloud catalog sync (админка → Evotor Cloud)
- CREATE product → сохранить `evotorUuid`
- PUT по uuid при обновлении
- `articleNumber = sc-<localProductId>` для дедупа
- ProductExtra: recipe, flags `#cup` / `#focus` при необходимости

### (Опционально) GET /api/devices/shift
```json
{ "open": true, "cashierName": "Иванов", "openedAt": "..." }
```
Только для подписи в header; не критично для продажи.

## Чего не делать на бэкенде кассы
- Не фискализировать «второй» чек.
- Не создавать новый Evotor product на каждый sync.
- Не отдавать секреты в APK.
