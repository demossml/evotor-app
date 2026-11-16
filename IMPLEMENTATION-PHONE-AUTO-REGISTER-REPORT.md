# PHONE AUTO-REGISTER — REPORT

## Версия

- `versionCode`: **173**
- `versionName`: **5.3.14-phone-auto-register**
- Тесты: **647 / 0**
- APK: `app/build/outputs/apk/release/app-release.apk`

## Поведение

| Ситуация | Касса |
|----------|-------|
| Сеть ок, клиент есть | Показывает баланс, effective %, «К заказу» |
| Сеть ок, клиента нет, согласие есть | Сразу вызывает `register` на backend и показывает профиль |
| Сеть нет / timeout / 5xx | Показывает «Нет связи с сервером, карту сейчас выдать нельзя», карту не создаёт |
| Согласия нет | `register` не вызывается, показывает «Отметьте согласие на обработку номера» |

## Что изменено

- Добавлен `net/PhoneCardService.kt` — единый серверный сценарий:
  - `lookup`;
  - если не найден и есть согласие — сразу `register(phone, "v1")`;
  - результат `Profile` только при HTTP 200 + `ok` + непустом `cardCode`;
  - сетевые ошибки и 5xx → `Error`, без локальной карты.
- `MainActivity.showPhoneCardSheet`:
  - чекбокс согласия перенесён на основную форму, до кнопки;
  - кнопка «Найти / Выдать карту» делает lookup + при необходимости register;
  - отдельный экран «Номер не найден, можно выпустить карту» удалён;
  - без галочки поиск не запускается, чекбокс подсвечивается ошибкой.
- `CardSession` / `cardCode`:
  - убран fallback `ifBlank { digits }` — телефон больше не может стать cardCode;
  - `CardSession` заполняется только после ответа backend с серверным `cardCode`;
  - при ошибке lookup/register профиль и сессия не создаются.

## Тесты

Добавлен `PhoneCardServiceTest`:

- not found + consent → `register` вызван, вернулся серверный `cardCode`;
- not found без consent → `register` не вызван;
- lookup network error → `register` не вызван;
- lookup throws IOException → `register` не вызван;
- register network error → результат `Error`, профиль не создан;
- register без `cardCode` → результат `Error`;
- серверный `cardCode` не подменяется.

## Не менял

- `CustomersApi`, `SellLauncher`, каталог;
- маску телефона (исправлена в предыдущей сборке);
- backend, SMS, процент начисления.
