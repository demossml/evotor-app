# Каталог 6.7 → касса: топинги, upsell, фото

## Канал
`GET https://app.67coffee.ru/api/devices/catalog`  
Тот же HTTPS + прокси Эвотор, что `POST /api/devices/loyalty/resolve`.  
Заголовок опционально: `X-Evotor-Store-Uuid`.

## JSON (v1)
- `products[]`: evotorUuid, toppings[], upsell[], imageUrl, recipe, countsAsCup, freeEligible
- `upsellDefault[]`: общие допы (#upsell в description или круассан)

## Поведение APK
1. Inventory Эвотор + merge catalog по uuid.
2. Тап по товару с toppings → sheet добавок → в корзину напиток + допы.
3. «К оплате» → рекомендации upsell → оплата / лояльность.

## Фото
- В админке: `products.image_url` (upload → `/uploads/...`).
- Backend в catalog отдаёт **абсолютный** URL (`PUBLIC_BASE_URL` + path).
- Касса пока показывает инициалы-плейсхолдер; URL уже в модели (`imageUrl`) — следующий шаг Coil.
- Рекомендуемые деривативы: WebP 128px (список), 256px (Фокус).

## Админка
- Схемы модификаторов → product.modifier_scheme_id
- #upsell в description для рекомендаций
- #focus для блока Фокус
