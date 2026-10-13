# Применение Android-архива на MacBook

1. Распаковать поверх репозитория evotor-app (или `6.7-evotor-app`).
2. Не коммитить `local.properties`, `*.apk`, keystore.
3. `export JAVA_HOME=…` и `ANDROID_HOME=…` как у вас для прошлых сборок.
4. `./gradlew :app:assembleDebug :app:testDebugUnitTest`
5. Проверить: versionCode=76, versionName=4.6.1-cashier-ui-safe, app_uuid без изменений.
6. Commit + push; APK залить в dev.evotor.ru / на тестовую кассу.

На ST5 после установки: цена = нативное меню, карта → resolve → чек → sc в облаке, фото при живом catalog.
