# IMPLEMENTATION — APK: кэшбэк рядом с ваучерами (5.2.10)

Промпт: `PROMPTS-VOUCHERS-N1-N3-APK-ONLY.md`, Часть II (APK), STEP-A00 → A04
Ветка: `vch/cashback-with-vouchers` · Сборка: **5.2.10-cashback-with-vouchers / versionCode 135**
Репозиторий `sixth-cup` (backend) не трогался — он в отдельном файле промптов.

## 1. Что было

В окне клиента кэшбэк показывался условием:

```kotlin
if (cbPossible && voucherList.isEmpty()) { … }
```

То есть у гостя, у которого есть **хотя бы один ваучер**, вариант «Списать кэшбэк» исчезал —
вместе с реферальными начислениями, которые лежат именно в кэшбэке. Гость мог потратить
ваучер, но не свои деньги.

## 2. Что сделано

| STEP | Изменение | Коммит |
|---|---|---|
| A00 | База: 269 тестов / 0, `134 / 5.2.9-client-screen`; карта мест с ваучерами | `docs(vouchers): apk baseline` |
| A01 | Новый `data/ClientOffers.kt` — чистая модель «что предложить» (подарок / кэшбэк / ваучеры + `any`) и 8 тестов. UI не менялся | `feat(cashier): ClientOffers model` |
| A02 | `openClientScreen` берёт три флага из модели; кэшбэк показывается **всегда**, когда он есть по резерву; пустое состояние — `!offers.any` | `fix(cashier): cashback stays available next to vouchers` |
| A03 | `135 / 5.2.10-cashback-with-vouchers`, сборка | `chore(cashier): bump version to 5.2.10-cashback-with-vouchers` |

Взаимоисключение сохранено (три лямбды, `MainActivity.kt`):

```kotlin
useGift = true; useCashback = false; selectedVoucher = null   // подарок
selectedVoucher = v; useGift = false; useCashback = false      // ваучер
useCashback = true; useGift = false; selectedVoucher = null    // кэшбэк
```

## 3. Тесты

| | До | После |
|---|---|---|
| `:app:testDebugUnitTest` | 269 / 0 | **277 / 0** (+8 `ClientOffersTest`) |
| `:app:lintDebug` | 0 errors | 0 errors |
| `:app:assembleRelease` | SUCCESS | SUCCESS |

Тесты A02 (`кэшбэк снимает ваучер`, `ваучер снимает кэшбэк`) **уже существовали** в
`CheckoutChoiceTest` (`cashback drops voucher`, `voucher drops cashback`) и покрывают ровно те
сценарии — дублировать не стал, зафиксировал в прогрессе.

## 4. Артефакт

| | |
|---|---|
| APK | `app/build/outputs/apk/release/app-release.apk` |
| Размер | 5 713 919 байт |
| versionCode / versionName | 135 / `5.2.10-cashback-with-vouchers` |
| Подпись | `CN=6.7 Coffee, OU=POS, O=6.7 Coffee, L=Yekaterinburg, C=RU` |

## 5. Чеклист для человека на терминале (не пройден — терминала нет)

1. Гость с кэшбэком и с ваучером (после включения ваучеров на backend): в окне клиента видны
   «Без льготы», ваучер(ы) **и** «Списать кэшбэк».
2. Выбор кэшбэка снимает ваучер, выбор ваучера снимает кэшбэк.
3. Гость без ваучеров: поведение как в 5.2.9.
4. Подарок за 1 ₽ по-прежнему работает (`logcat -s SellLauncher` → `fiscal_positions … gift=true`).
5. Тот же QR повторно — без «Заменить карту».

## 6. Что не сделано и почему

- Ваучеры физически появятся в окне клиента только после backend N1 и флага
  `VOUCHERS_IN_RESOLVE=1` — это Часть I, другой репозиторий.
- Push не делал: по правилам серии `git push` запрещён без владельца.
