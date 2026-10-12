# Фото на кассе (v73)

## Поток
Админка upload → products.image_url → GET /api/devices/catalog (imageUrl absolute) → APK ThumbLoader.

## UI
- Список 48dp, Фокус 72dp, CENTER_CROP, круглый clip.
- Нет URL → инициалы.
- Ошибка сети → инициалы (ImageView пустой поверх).

## Сервер
PUBLIC_BASE_URL=https://app.67coffee.ru
Картинки с того же хоста, что API (TLS/прокси Эвотор).

## Рекомендации дизайнеру
WebP/JPEG, длинная сторона 256–512 px, < 150 KB.
