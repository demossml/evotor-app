# Шестой стакан — касса Эвотор v1.2

versionCode **17** · package `ru.sixthcup.evotor`  
app_uuid `151071e8-88a4-44f6-b71a-b17c559f9b7d`

## Что изменилось

1. **Нет QR клиенту** после оплаты.
2. **Реальная пробивка:** `OpenSellReceiptCommand` → экран оплаты Эвотора → печать фискального чека.
3. Цены в чеке уже с купоном / 6-м стаканом / кэшбэком (`priceWithDiscountPosition`).

Документация Эвотор:
- https://developer.evotor.ru/docs/doc_java_receipt_creation.html
- https://developer.evotor.ru/docs/doc_java_in_app_receipt_payment.html

## Сборка

```bash
export JAVA_HOME=...
export ANDROID_HOME=$HOME/Library/Android/sdk
cd evotor-app
./gradlew :app:assembleDebug
```

Перед заливкой: versionCode > последнего на portal.

## Проверка на кассе

1. Плитка «Шестой стакан»
2. Меню → товары → карта (опционально)
3. «Оплатить» → должен открыться **экран оплаты Эвотора**
4. Оплатить наличные/карту → **фискальный чек печатается**
5. Вернуться → «Новая продажа» без QR
