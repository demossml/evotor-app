# Release-подпись APK

Начиная с **5.1.2 (versionCode 117)** release-сборка подписывается постоянным ключом
6.7 Coffee. Ключ и пароль хранятся **вне репозитория**; в git лежит только этот файл
с открытыми данными сертификата.

| Параметр | Значение |
|---|---|
| Alias | `sixth-cup` |
| Владелец (DN) | `CN=6.7 Coffee, OU=POS, O=6.7 Coffee, L=Yekaterinburg, C=RU` |
| Ключ | RSA 4096, подпись `SHA384withRSA` |
| **SHA-256 сертификата** | `B5:17:13:E9:6C:83:BD:CF:99:CF:C2:78:30:83:87:04:BB:2E:74:4F:12:EA:6F:42:E6:81:E2:7D:77:8C:1A:7D` |
| SHA-256 публичного ключа | `18:D4:E2:A5:F1:73:E3:74:D5:44:81:7F:E2:A8:94:C5:9F:02:5D:97:8C:96:57:6B:2A:0B:B8:66:3B:5D:F6:7A` |
| Создан | 2026-10-25 |
| Действителен до | 2056-10-17 (10950 дней) |
| Файл ключа | `~/.android-keys/sixth-cup-release.jks` (chmod 600, вне репозитория) |
| Резервная копия | `~/.android-keys/backup-2026-10-25.jks` |
| Пароль | в менеджере паролей владельца и в `~/.android-keys/sixth-cup-release.PASSWORD.txt` (chmod 600) |

## Сборка

```bash
KEYSTORE_FILE=$HOME/.android-keys/sixth-cup-release.jks \
KEYSTORE_PASSWORD='<из менеджера паролей>' \
KEY_ALIAS=sixth-cup \
KEY_PASSWORD='<из менеджера паролей>' \
./gradlew clean :app:testDebugUnitTest :app:lintDebug assembleRelease
```

Без этих переменных `assembleRelease` по-прежнему падает на debug-ключ машины
(`app/build.gradle.kts`, `signingConfig = …findByName("release") ?: …getByName("debug")`) —
такой APK нельзя считать релизным.

## Проверка подписи

```bash
apksigner verify --print-certs -v app/build/outputs/apk/release/app-release.apk | grep -E "DN|SHA-256"
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -1
```

Ожидается `CN=6.7 Coffee, …` и SHA-256 `b51713e9…c1a7d`. Debug-сертификат машины
(`3B:9C:4E:D3:…:9B:18`, `CN=Android Debug`) в релиз больше попадать не должен.
Схемы подписи: v1 + v2 (v3 apksigner включает только с `minSdk ≥ 28`; на терминалах
Эвотор с Android 5.1–8.1 используется v1/v2).

## Смена ключа — разовое действие

Предыдущие сборки были подписаны debug-ключом этой машины. Android не устанавливает APK
с другим ключом поверх существующего: требуется `adb uninstall ru.sixthcup.evotor`,
а это стирает локальные данные терминала (настройки точки, очередь заказов, состояние
смены). Удаление планируется отдельно; после него все обновления (118, 119, …)
ставятся поверх без удаления — при условии, что ключ не меняется.

## Хранение

- Ключ существует в единственном экземпляре на этой машине + резервная копия рядом.
  Копию нужно положить в менеджер паролей или на внешний носитель: потеря ключа =
  невозможность обновлять установленные кассы.
- `*.jks`, `*.keystore`, `*.apk`, `local.properties` закрыты `.gitignore` — в репозиторий
  не попадают; пароль в этот файл и в историю git не записывается.
