# 6.7 Coffee — касса Эвотор v1.3

versionCode **18** · package `ru.sixthcup.evotor`  
app_uuid `151071e8-88a4-44f6-b71a-b17c559f9b7d`

## Что в этой версии (логика, без редизайна)

1. **Меню с backend** — `GET /api/directory` (товары владельца из admin). Хардкод каталога убран.
2. **Регистрация кассы** — `POST /api/devices/enroll` с кодом из admin → Кассы.
3. **Кэш каталога** офлайн + кнопка «Обновить меню с сервера».
4. **Очередь loyalty-чеков** → `POST /api/devices/sync` с device token.
5. Фискальная пробивка через Эвотор SDK — как в 1.2.

## Настройка

При enroll укажите URL API, например:

- `https://app.67coffee.ru`
- или IP Mac mini в LAN

Код кассы: **admin.*** → вкладка «Кассы» → код enroll.

## Сборка

```bash
export JAVA_HOME=...
export ANDROID_HOME=$HOME/Library/Android/sdk
cd evotor-app-main
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`  
Перед заливкой: **versionCode >** последнего на portal.

## Проверка

1. В admin создайте товары и код кассы.
2. На Эвоторе: код + URL → регистрация.
3. Меню = товары с сервера.
4. Оплатить → экран оплаты Эвотора → чек.

## Добавки (сиропы / топпинги)
При + к напитку — диалог добавок. Настройка с admin/backend — см. docs/PLAN-MODIFIERS.md
