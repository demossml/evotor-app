# Пакет исправлений 6.7 Coffee Evotor

Базовый проект: `evotor-app-main (5).zip`.
Наложены четыре исходных файла из `evotor-fixes-p0-p12.zip`: `Cart.kt`, `SellLauncher.kt`, `MainActivity.kt`, `activity_main.xml`.

Версия сборки установлена в `app/build.gradle.kts`: `versionCode=81`, `versionName=4.6.6-audit-fixes-p0-p12`. Это следующий build number относительно базового Gradle-конфига (`versionCode=80`).

В архив добавлен `AGENT-PROMPT-BUILD-4.6.6.txt` с инструкцией агенту на MacBook.

Важно: этот архив не содержит подтверждённого APK. В текущей среде Gradle wrapper не смог скачать Gradle из-за недоступности `services.gradle.org`; сборка и тесты должны быть запущены на MacBook. До установки на рабочую кассу обязательна проверка фискального чека на тестовом терминале.
