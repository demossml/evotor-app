# Настройка в dev.evotor.ru — 6.7 Coffee (`ru.sixthcup.evotor`)

## Домены

| Назначение | URL |
|------------|-----|
| API + webhook’и + PWA | **https://app.67coffee.ru** |
| Админка владельца | https://admin.67coffee.ru (не для Evotor webhook) |
| Cloud API Эвотор | https://api.evotor.ru (только **наш backend**, не касса) |

**app_uuid** (в манифесте): `151071e8-88a4-44f6-b71a-b17c559f9b7d`

**versionCode** этой сборки: **40** (должен быть больше уже залитого в кабинет).

---

## 1. APK

1. Собрать: `./gradlew :app:assembleDebug`
2. Файл: `app/build/outputs/apk/debug/app-debug.apk`
3. Загрузить в версию приложения на dev.evotor.ru
4. Установить на тестовую кассу **из Личного кабинета Эвотор** (не только ADB), чтобы облачные URL применились

В этой сборке есть `DiscountIntegrationService`: при продаже пишет в чек тестовый `extras.sc` (скидка 0 ₽) — для проверки FACTS **0.6**.

---

## 2. Вкладка «Интеграция» — URL

База: `https://app.67coffee.ru`

| Поле в кабинете | URL | Метод |
|-----------------|-----|--------|
| Регистрация / create user | `https://app.67coffee.ru/api/v1/user/create` | POST |
| Авторизация / verify user | `https://app.67coffee.ru/api/v1/user/verify` | POST |
| Токен Облака | `https://app.67coffee.ru/api/v1/user/token` | POST |
| Документы (если включают) | `https://app.67coffee.ru/api/v1/evotor/documents` | PUT (уточнить слэш в UI) |

Авторизация webhook (как выберете в UI):

- **Bearer** — придумайте секрет → тот же в серверный `.env` как `EVOTOR_WEBHOOK_TOKEN`
- или Basic Auth

После **установки** приложения на магазин облако шлёт Cloud Token на `/user/token`.  
Его используют для REST: `X-Authorization` / кладут в `EVOTOR_API_TOKEN` для `npm run evotor:probe`.

---

## 3. Push — нужны ли сейчас?

| Задача | Push |
|--------|------|
| Чеки SELL → наш сервер | **Нет** (опрос API с backend каждые ~60 с) |
| Ускорить документы | Webhook документов — опционально |
| Обновить SC-CONFIG на кассе | Позже, когда появится `EVOTOR_APP_ID` |
| Phase 0 / проверка extras | **Не нужны** |

**Сейчас push в кабинете можно не настраивать.**

---

## 4. Прокси / разрешённые URL с терминала

По схеме Cloud **касса не ходит** на `app.67coffee.ru`.  
Список «разрешённых URL» для прямых запросов приложения к вашему серверу **не обязателен**.

Старый Stage1 proxy-токен для этой схемы не требуется.

---

## 5. Какие токены где

| Токен | Где берётся | Куда |
|-------|-------------|------|
| Токен приложения (ваш секрет) | Задаёте в «Интеграция» | `.env` → `EVOTOR_WEBHOOK_TOKEN` |
| Cloud Token | Webhook `/user/token` после установки **или** вручную | `.env` → `EVOTOR_API_TOKEN` |
| mk@evotor.ru | Только Мобильный кассир | **Не** эта схема |

---

## 6. Важно: backend webhook-маршруты

Phase 0/1 backend уже умеет **client + poll + probe**.  
Маршруты `/api/v1/user/*` могут ещё **не быть** реализованы.

Пока 404:

- URL в кабинете всё равно пропишите;
- Cloud Token возьмите вручную → `EVOTOR_API_TOKEN` → probe;
- либо попросите backend-агента добавить stub: POST create/verify/token → 200 + лог (без секрета в лог).

---

## 7. Чеклист

- [ ] APK versionCode > текущей в кабинете (40+)
- [ ] app_uuid совпадает
- [ ] URL create / verify / token на https://app.67coffee.ru/...
- [ ] Секрет Bearer = сервер
- [ ] Установка приложения на кассу из ЛК
- [ ] Продажа → probe/poll → есть ли extras (0.6)
- [ ] Push — пропуск
