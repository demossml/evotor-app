# Задание агенту: накатить UI index → Evotor APK и собрать

## Цель
Полный UI-port кассового потока из дизайна `index` (блоки 01–10) в Android-приложение `ru.sixthcup.evotor`.  
Логику Эвотора, Inventory, SellLauncher, LoyaltyApi, CatalogApi **не ломать**.

## Версия сборки
Уже выставлено в `app/build.gradle.kts`:
- **versionCode = 86**
- **versionName = 4.7.0-index-ui**

Если у тебя на Mac локально versionCode уже ≥ 86 — поставь **следующий** свободный (87, 88…).

## Что менялось (ключевые файлы)
```
app/src/main/java/ru/sixthcup/evotor/ui/MainActivity.kt   # POS UI 01–10
app/src/main/java/ru/sixthcup/evotor/ui/CatalogAdapter.kt  # focus + tile
app/src/main/java/ru/sixthcup/evotor/ui/UiKit.kt           # токены base.css
app/src/main/res/values/colors.xml
app/src/main/res/drawable/ic_logo_67.xml                  # NEW
app/build.gradle.kts                                      # versionCode 86
```
Остальной код (data/, net/, sell/, scanner/, integration/) — как в исходном проекте.

## Как накатить
1. Распаковать этот архив.
2. Сравнить/скопировать изменённые файлы поверх рабочей копии `evotor-app-main` на Mac  
   **или** открыть этот проект целиком в Android Studio.
3. Не удалять интеграцию Эвотора (`integration-library`, grants, SellLauncher).

## Сборка
```bash
cd evotor-app-main
./gradlew :app:assembleDebug -PAPI_BASE_URL=https://app.67coffee.ru
# APK:
# app/build/outputs/apk/debug/app-debug.apk
```
Опционально unit-тесты:
```bash
./gradlew :app:testDebugUnitTest
```

## Установка на кассу
```bash
adb connect <IP_ТЕРМИНАЛА>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Или через кабинет Эвотора (тестовая версия приложения).

## API бэкенда (APK уже вызывает — не выдумывать)
1. `GET {API_BASE_URL}/api/devices/catalog`
2. `POST {API_BASE_URL}/api/devices/loyalty/resolve` body `{"code":"..."}`

Секретов в APK нет. Authorization — через прокси Эвотора.

## Что НЕ в scope
- Табель / пожелания смен / HR из system.html
- Собственная фискализация (только Эвотор)
- Эмуляция оплаты «картой/наличными» вместо SellLauncher

## Чеклист после установки на ST5
- [ ] Каталог из Inventory + фото/топпинги с catalog API
- [ ] Focus «Сейчас берут», чипы, плитки
- [ ] Карта QR/номер → guest chip
- [ ] Item sheet → корзина → cartbar
- [ ] К оплате → upsell → loyalty → экран Эвотора
- [ ] После SELL: PendingPayment → «Чек пробит» / «Не оплачено»
