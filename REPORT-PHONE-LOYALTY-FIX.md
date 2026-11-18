# Phone loyalty fix — APK 5.3.18

Дата: 2026-11-18  
Версия: `versionCode=177`, `versionName=5.3.18-phone-loyalty-fix`

## Что восстановлено

- Ввод телефона на кассе снова идёт через **отдельный `resolveByPhone`**:
  `POST /api/devices/loyalty/resolve { "phone": "..." }`.
- Backend сам находит или создаёт карту по номеру и возвращает `cardCode`, баланс и `canRedeem`.
- Если backend вернул `canRedeem=true`, APK разрешает списание кэшбэка по телефону:
  `CardSession.cashbackRedeemable` для `identifiedBy=PHONE` больше не требует QR, резерва
  или какого-либо подтверждения на устройстве клиента.
- Числовой код карты (`identifiedBy=CODE`) по-прежнему не даёт списания.
- QR-сценарий сохранён: `canRedeem` + живой резерв + подписанный токен.
- Подсказка в карте для телефонной карты исправлена: «Бонусы можно списать на кассе»,
  а не «только по QR клиента».

## Путь сценария

1. Кассир вводит телефон.
2. `PhoneCardService.findOrCreate` → `customers/lookup` / `customers/register`.
3. `LoyaltyApi.resolveByPhone(phone)` → `loyalty/resolve` с `phone`.
4. Backend возвращает `cardCode`, баланс, `canRedeem=true` (по бонусной политике backend).
5. APK сохраняет `CardInfo` с `identifiedBy=PHONE`.
6. Кассир нажимает «Списать бонусы» → `openBonusSpendSheet`.
7. `CheckoutChoice` пишет `cb` в `extras.sc`; `SellLauncher`/`FiscalCalc` применяют скидку;
   backend при обработке SELL списывает кэшбэк по карте.

## Проверка

- `./gradlew testDebugUnitTest` — **657 тестов, 0 падений**.
- `./gradlew assembleRelease` — успешно.
- APK: `app/build/outputs/apk/release/app-release.apk`, версия 177.
