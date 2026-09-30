# Установка «Шестой стакан» на кассу Эвотор

Официальные источники:
- https://developer.evotor.ru/docs/doc_app_installation.html
- https://developer.evotor.ru/docs/doc_java_app_manifest.html
- https://developer.evotor.ru/docs/doc_app_developer_mode.html
- https://dev.evotor.ru

Package: `ru.sixthcup.evotor`  
versionCode: смотри `app/build.gradle.kts`

---

## 0. Сборка APK (на компьютере с Android Studio)

1. Установи [Android Studio](https://developer.android.com/studio).
2. Открой папку `evotor-app` как проект.
3. В `gradle.properties` и в `AndroidManifest.xml` подставь свой **app_uuid** с портала (после шага 1 ниже).
4. Build → Build Bundle(s) / APK(s) → Build APK(s).
5. Файл: `app/build/outputs/apk/debug/app-debug.apk` (или release).

Каждый новый APK на портал: **увеличь versionCode** на 1.

---

## 1. Портал разработчика (обязательно)

1. Зайди на https://dev.evotor.ru
2. **Добавить новое** приложение.
3. Платформа: **Эвотор**.
4. Включи **«Приложение с APK»**.
5. Скопируй **app_uuid**.
6. Вставь uuid в:
   - `app/src/main/AndroidManifest.xml` → meta-data `app_uuid`
   - `gradle.properties` → `APP_UUID=...`
7. На вкладке **APK**:
   - либо **Загрузить APK**,
   - либо **Ввести ID пакета вручную** → `ru.sixthcup.evotor`
8. Вкладка **Тестирование**: добавь телефон, на который зарегистрирован терминал.
9. Переведи версию в статус **«Тестирование»**.

Без совпадения `app_uuid` + package установка на терминал не пройдёт.

---

## 2. Способ A — из Магазина (рекомендуется для теста)

1. На терминале **выключи** режим разработчика, если ставишь из магазина  
   (если developer mode включён — только ADB, см. способ B).
2. На телефоне/ЛК того же аккаунта-тестировщика открой:
   ```
   https://market.evotor.ru/store/apps/<твой-app_uuid>
   ```
3. Установи приложение на нужный смарт-терминал (галочка терминала → Применить).
4. На главном экране Эвотора появится плитка **«Шестой стакан»** (синий фон #002FA7).

---

## 3. Способ B — ADB

1. Включи **режим разработчика** на терминале  
   (по инструкции: https://developer.evotor.ru/docs/doc_app_developer_mode.html — телефон терминала в тестировщиках на портале).
2. Терминал и ПК в одной Wi‑Fi.
3. Узнай IP терминала (Настройки сети).
4. На ПК (нужен `adb` из Android SDK):

```bash
adb connect <IP_ТЕРМИНАЛА>:5555
adb install -r app-debug.apk
```

5. Если «not found»: `adb kill-server && adb start-server` и снова connect.

---

## 4. Первый запуск на кассе

1. Открой плитку **Шестой стакан**.
2. Введи код регистрации (для dev веб-бэкенда: `DEMO1234`) → «Зарегистрировать кассу».
3. Сканируй QR карты клиента сканером Эвотора **или** вставь токен вручную.
4. Введи стаканы / бесплатный / кэшбэк / сумму → «Подтвердить».
5. Покажи QR чека клиенту.

Фискальный чек по-прежнему бьётся **штатной продажей Эвотора**. Это приложение — только лояльность.

---

## 5. Типичные ошибки

| Проблема | Что проверить |
|----------|----------------|
| Нет плитки на главном | `category.EVOTOR` в манифесте, приложение установлено |
| Не ставится из магазина | Статус «Тестирование», телефон в тестировщиках, app_uuid совпадает |
| versionCode | Новый APK должен иметь больший versionCode |
| Сканер молчит | permission `SCANNER_RECEIVER`, receiver на `ScannedCode`; есть ручной ввод |
| Нет сети к API | Пока enroll локальный; API_BASE_URL в BuildConfig для следующей версии |

---

## 6. Иконка

Простые синие круги без текста: `res/mipmap-*/ic_launcher.png`, для Маркета `icon-300.png` (300×300).
