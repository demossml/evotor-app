# STEP-VCH-APK-PROGRESS

Промпт: `PROMPTS-VOUCHERS-N1-N3-APK-ONLY.md` (Часть II, APK) · ветка `vch/cashback-with-vouchers`

## STEP-A00 — Базовая точка (APK)

Статус: DONE
Коммит: см. `docs(vouchers): apk baseline`
Что сделано: ветка создана от `main` (`8652010`), база тестов и карта мест с ваучерами записаны.
Тесты: база `./gradlew :app:testDebugUnitTest` — **269 passed, 0 failed**; lint не запускался (по плану — в A01/A02)
Изменённые тесты: нет
DEFAULT-AD-HOC: нет

| Параметр | Значение |
|---|---|
| HEAD при старте | `8652010 docs: 1 RUB gift confirmed on the terminal` |
| versionCode / versionName | **134** / `5.2.9-client-screen` |

Карта мест, где ваучеры влияют на показ льгот (`grep` по `MainActivity.kt` и `data/*.kt`):

| Файл:строка | Строка | Роль |
|---|---|---|
| `ui/MainActivity.kt:3101` | `val voucherList = card.vouchers` | источник списка ваучеров в окне клиента |
| `ui/MainActivity.kt:3153` | `for (v in voucherList) {` | перебор ячеек ваучеров |
| `ui/MainActivity.kt:3164` | `if (cbPossible && voucherList.isEmpty()) {` | **дефект:** кэшбэк скрыт, когда у гостя есть ваучеры |
| `ui/MainActivity.kt:3179` | `if (!giftPossible && voucherList.isEmpty() && !cbPossible) {` | условие «льгот нет» |
| `data/LoyaltyBenefit.kt:111` | `return card.freeAvailable > 0 \|\| card.vouchers.isNotEmpty()` | `canOffer` (частота окна, не показ кэшбэка) |

Ожидание промпта подтвердилось: условие скрытия кэшбэка найдено ровно одно — `MainActivity.kt:3164`.

## STEP-A01 — Чистая модель предложений окна клиента

Статус: DONE
Коммит: `feat(cashier): ClientOffers model`
Что сделано: новый `data/ClientOffers.kt` (gift / cashback / vouchers + `any`) и 8 тестов; UI не менялся.
Тесты: `ClientOffersTest` — 8 passed; охранный набор `:app:testDebugUnitTest` — **277 passed, 0 failed**; `:app:lintDebug` — 0 errors
Изменённые тесты: нет
DEFAULT-AD-HOC: нет
