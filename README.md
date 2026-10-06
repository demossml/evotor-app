# 6.7 Coffee — кассовое приложение Эвотор

Package: `ru.sixthcup.evotor`  
VersionCode: **53**  
VersionName: **2.3.2-evotor-catalog**  
App UUID: `151071e8-88a4-44f6-b71a-b17c559f9b7d`

## Архитектура

APK не содержит собственного хардкод-каталога и не заменяет кассовый движок Эвотора.

- Эвотор Cloud хранит базовую номенклатуру и доставляет её на терминал.
- Backend 6.7 создаёт/обновляет товары через Cloud API и сохраняет выданный Эвотор UUID.
- Рецепт, топпинги и бизнес-метаданные 6.7 передаются как `ProductExtra`.
- `EvotorCatalogActivity` читает локальный inventory Эвотора через `InventoryApi`, показывает рецепт/топпинги и возвращает выбранный товар в чек через `ru.evotor.createPosition`.
- Фискализация и закрытие чека остаются за Эвотором.
- Loyalty: signed QR или короткий номер карты; итоговое начисление выполняется сервером после `SELL` через poll/SellHandler.

## Клиент

На главном экране доступен ручной ввод короткого номера карты. Сканер принимает либо signed QR, либо цифровой short code.

Пользовательский сценарий:

1. Сканировать QR гостя **или** ввести номер карты.
2. Открыть меню товаров Эвотора.
3. Выбрать товар; APK показывает рецепт и доступные для этого товара добавки.
4. APK возвращает существующий Evotor product UUID в текущий чек.
5. Эвотор фискализирует чек.
6. Backend после polling обрабатывает `extras.sc` и начисляет loyalty.

## Сборка

```bash
./gradlew :app:assembleDebug \
  -PSERVER_PUBLIC_KEY=<base64url-ed25519-public-key> \
  -PSERVER_KEY_ID=<key-id> \
  -PCUPS_FOR_FREE=5
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Перед выпуском увеличивай `versionCode`.
