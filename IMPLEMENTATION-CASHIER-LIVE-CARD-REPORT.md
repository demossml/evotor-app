# IMPLEMENTATION — CASHIER LIVE CARD / QUEUE / POST-PAY (evotor-app)

**Дата:** 2026-10-27
**Репозиторий:** `evotor-app` (`~/Documents/evotor-app`), ветка `main`
**Итог:** **5.1.5 / versionCode 120**
**Источник:** `PROMPTS-CASHIER-LIVE-CARD-QUEUE-SEQUENTIAL.md`; контекст — `RESEARCH-LOYALTY-AUDIT-67-FINAL.md`.

## Симптомы владельца и что с ними стало

| # | Симптом | Причина | Результат |
|---|---|---|---|
| 1 | Ручной ввод: «0 кружек из 5», хотя `paid_total=5` | Backend для `kind=code` отдаёт `paidCups = paid_total % cupsForFree` (=0 при 5) и обнуляет `freeAvailable`/`cashback`; APK печатал это как «набрано чашек» | Прогресс и подарок считает `CardProgress`. При `paidCups ≥ goal` или при наличии `paidTotal`/`freeUsed` показывається реальный прогресс и «Подарок доступен» |
| 2 | Подарок заработан, но не виден | Признак подарка был только `freeAvailable > 0`, а он обнулён для кода | Добавлен `giftAvailable` (независимо от права списания) + текст «списание — по QR-карте» |
| 3 | Карта «залипает» после чека | `CardSession` — снимок на 10 минут, обновлялся только при новом скане | `CardSession.refreshFromServer` + триггеры (после продажи, кнопка, открытие карты) + poll 30 с |
| 4 | Нет номера очереди и баллов после оплаты | После закрытия чека сразу уходили в очередь, номер нигде не показывался | Экран post-pay: «Заказ № NN», состав, сумма, карта, остаток подарка/бонусов |

## Коммиты

| SHA | Шаг | Сообщение |
|---|---|---|
| `ae2ce2c` | C00 | `docs(cashier): live card progress baseline` |
| `4a55773` | C01 | `fix(loyalty): show cup progress for manual code without enabling redeem` |
| `0384b1d` | C02 | `feat(loyalty): CardSession.refreshFromServer` |
| `7948f2f` | C03 | `feat(loyalty): refresh card after sale and on demand` |
| `697cb82` | C04 | `feat(loyalty): periodic card refresh while attached` |
| `d127089` | C05+C06 | `feat(cashier): daily order queue number` (включает post-pay экран) |
| `d946a51` | C07 | `chore(apk): bump after cashier live card UX` |

Отклонение: C05 и C06 объединены — правки в одном файле, разделение было бы искусственным.

## Изменённые файлы

**Код**
- `data/CardProgress.kt` (новый) — прогресс, подарок, право списания, «известность» данных
- `data/CardRefresh.kt` (новый) — credential, применение ответа (`UPDATED/DROPPED/KEPT_STALE`), «заметное изменение»
- `data/CardPoll.kt` (новый) — гейт опроса 30 с
- `data/CardSession.kt` — `paidTotal`/`freeUsed`, `refreshFromServer`, флаг устаревания
- `net/LoyaltyApi.kt` — `parseResolve` с алиасами полей
- `ui/MainActivity.kt` — три точки отображения прогресса через `CardProgress`, кнопка «Обновить», poll, `Screen.POSTPAY` + `renderPostPay`
- `app/build.gradle.kts` — 5.1.5 / 120

**Тесты**
- `CardProgressTest` (10), `CardRefreshTest` (7), `CardPollTest` (5), `OrderNumberDailyTest` (6)

**Документы**
- `STEP-CASHIER-LIVE-CARD-PROGRESS.md`, этот отчёт

Backend `sixth-cup` не изменялся.

## Проверки

| Проверка | Результат |
|---|---|
| `:app:testDebugUnitTest` | **167 тестов, 0 падений, 0 ошибок** (было 139) |
| `:app:lintDebug` | **0 errors, 111 warnings** (baseline не менялся) |
| `:app:assembleRelease` | BUILD SUCCESSFUL |
| L-00 регресс | `CardSessionExtrasTest` — 11/11 green |

**APK:** `app/build/outputs/apk/release/app-release.apk`, **5 684 403 байт**, `versionCode=120`, `versionName=5.1.5`, подпись `CN=6.7 Coffee, OU=POS, O=6.7 Coffee, L=Yekaterinburg, C=RU`.

## Что видит бариста

**Ручной код, на сервере 5 чашек:**
```
Карта ••12
ЧАШКИ 5 / 5        (или 0 / 5, если сервер прислал только остаток цикла)
БОНУСЫ —
Подарок доступен · списание по QR-карте
[Подарок] недоступен
```

**QR с резервом:**
```
Подарок доступен
[Подарок] включён, списание проходит
```

**После оплаты:**
```
ЧЕК ЗАКРЫТ
Заказ № 07
Капучино × 2 · 400 ₽
[панель карты: чашки, подарок, бонусы, «Обновить»]
[К баристе]  Новый заказ
```

## Остаточный риск и что не закрыто

1. **Backend не отдаёт информацию для ручного кода.** `reservations.ts:74-76` обнуляет `freeAvailable`/`cashback` и `paidCups` = остаток цикла. Поэтому «5/5 + подарок» по коду показывается только если сервер пришлёт `paidCups ≥ goal` или `paidTotal`. Правильное решение — 3 строки в backend (info-поля, списание всё равно невозможно без резерва); **требует отдельного разрешения**, в этой серии backend не менялся.
2. **Свежие чашки после чека приходят с задержкой** — backend обрабатывает SELL в poll (по умолчанию 60 с). Первое обновление после продажи может вернуть старые данные; исправляет следующий poll карты (30 с).
3. **E2E на кассе не выполнялся**: установка, реальный QR, post-pay экран, poll на живом терминале.
4. **Номер заказа** уникален в пределах эпохи (смена Эвотора или календарные сутки), после 99 идёт по кругу с пропуском занятых.
5. **`sc.c` / L-00** не менялся; `free=1` по ручному коду не отправляется ни при каких условиях (тесты extras + `CardProgress.redeemEnabled`).

## Не делалось

Push, установка APK на кассу, деплой, изменения рабочей БД и backend-репозитория.
