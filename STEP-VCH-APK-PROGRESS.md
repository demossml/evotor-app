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

## STEP-A02 — Окно клиента использует `ClientOffers`: кэшбэк виден при ваучерах

Статус: DONE
Коммит: `fix(cashier): cashback stays available next to vouchers`
Что сделано: в `openClientScreen` три условия заменены на модель; `if (cbPossible && voucherList.isEmpty())`
стало `if (cbPossible)`; пустое состояние — `if (!offers.any)`.
Тесты: `CheckoutChoiceTest` — оба требуемых сценария **уже есть** (`voucher drops cashback`, `cashback drops voucher`), не дублировал; охранный набор — 277 passed, 0 failed; `:app:lintDebug` — 0 errors
Изменённые тесты: нет
DEFAULT-AD-HOC: нет

Проверка по пункту 4 промпта: `grep -n "voucherList.isEmpty" MainActivity.kt` → совпадений нет.
Взаимоисключение в трёх лямбдах (строки 3151/3162/3173):

```kotlin
3151: useGift = true; useCashback = false; selectedVoucher = null; paint()   // подарок
3162: selectedVoucher = v; useGift = false; useCashback = false; paint()      // ваучер
3173: useCashback = true; useGift = false; selectedVoucher = null; paint()    // кэшбэк
```

Diff по `MainActivity.kt`: 7 добавлений / 5 удалений — только перечисленные строки.

## STEP-A03 — Версия и сборка

Статус: DONE
Коммит: `chore(cashier): bump version to 5.2.10-cashback-with-vouchers`
Что сделано: `versionCode = 135`, `versionName = "5.2.10-cashback-with-vouchers"`, больше в `build.gradle.kts` ничего не менялось.
Тесты: `:app:testDebugUnitTest` — 277 passed, 0 failed; `:app:lintDebug` — 0 errors; `:app:assembleRelease` — BUILD SUCCESSFUL
Изменённые тесты: нет
DEFAULT-AD-HOC: нет

| Артефакт | Значение |
|---|---|
| APK | `app/build/outputs/apk/release/app-release.apk` |
| Размер | 5 713 919 байт |
| versionCode / versionName | 135 / `5.2.10-cashback-with-vouchers` (aapt badging) |
| Подпись | `CN=6.7 Coffee, OU=POS, O=6.7 Coffee, L=Yekaterinburg, C=RU` (apksigner) |

## STEP-A04 — Отчёт и цикл перепроверки

Статус: DONE
Коммит: `docs(cashier): implementation report`
Что сделано: пройден чеклист перепроверки (11 пунктов), написан `IMPLEMENTATION-VCH-APK-REPORT.md`.
Тесты: `:app:testDebugUnitTest` — 277 / 0; `:app:lintDebug` — 0 errors; `:app:assembleRelease` — SUCCESS
Изменённые тесты: нет
DEFAULT-AD-HOC: нет

Чеклист (проход 1 — все пункты закрыты, второй проход не потребовался):

```
[x] 1. testDebugUnitTest — 277 / 0 (база 269 + 8; +2 из A02 не нужны: тесты уже были)
[x] 2. lintDebug — 0 errors
[x] 3. assembleRelease — BUILD SUCCESSFUL
[x] 4. grep "voucherList.isEmpty" MainActivity.kt — пусто
[x] 5. ClientOffers: ClientOffers.kt:9 (определение) + MainActivity.kt:3102 (использование)
[x] 6. diff 8652010..HEAD: ClientOffers.kt, ClientOffersTest.kt, MainActivity.kt,
       build.gradle.kts, STEP-VCH-APK-PROGRESS.md (+ отчёт A04)
[x] 7. Запрещённые файлы (scanner, CardSession, FiscalDiscount, SellLauncher,
       DiscountIntegrationService, LoyaltyApi, Voucher, Choice, DiscountCalc,
       CatalogAdapter, Cart) — diff пуст
[x] 8. Взаимоисключение в трёх лямбдах: 3151 / 3162 / 3173
[x] 9. versionCode 135, versionName 5.2.10-cashback-with-vouchers
[x] 10. @Ignore и закомментированных тестов нет
[x] 11. Прогресс содержит A00…A03 со статусом DONE
```
