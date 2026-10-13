# 4.6.1-cashier-ui-safe (versionCode 76)

Пакет для установки: UI из v75 без изменения контрактов лояльности.

## Берём
- RecyclerView + DiffUtil, debounce поиска, две фазы каталога (Inventory → enrichment)
- Чипы групп, блок «Фокус», sticky-корзина, insets
- ThumbLoader (фото с `/api/devices/catalog`), топинги sheet, upsell
- Цены PRICE_OUT/100 (KOPECKS), free-cup rules, ProductMarkers

## Не меняли (анти-регресс)
- extras.sc v2 (c, op, free, cb, ts)
- LoyaltyApi resolve, CardSession, DiscountCalc, DiscountIntegrationService
- SellLauncher / openSellReceipt
- applicationId `ru.sixthcup.evotor`, app_uuid `151071e8-…`, usesCleartextTraffic=false
- API_BASE_URL по умолчанию https://app.67coffee.ru

## Сборка
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```
APK: `app/build/outputs/apk/debug/app-debug.apk`
