# STEP-CASHIER-LIVE-CARD-PROGRESS

Серия: прогресс карты, live refresh, очередь, баллы на post-pay (`PROMPTS-CASHIER-LIVE-CARD-QUEUE-SEQUENTIAL.md`).
Репозиторий: `evotor-app`. Backend `sixth-cup` — только read-only сверка.

## STEP-C00 — Baseline (2026-10-27)

| Поле | Значение |
|---|---|
| HEAD на старте | `765e895` (`docs(loyalty): APK L-00 implementation report`) |
| `versionCode` / `versionName` | **119 / 5.1.4** |
| Предыдущая серия | L-00 (A00–A06): `sc.c` = токен; тесты extras green — сохраняются |

### Файлы

| Что | Где |
|---|---|
| Парсинг resolve | `net/LoyaltyApi.kt:17-75` |
| Модель карты | `data/CardSession.kt` (`CardInfo`), хранится в SharedPreferences, TTL 10 мин |
| Блок карты (тёмная плашка) | `ui/MainActivity.kt:470-501` |
| Блок карты в корзине | `ui/MainActivity.kt:2160-2212` |
| Строка статуса карты | `ui/MainActivity.kt:1909` |
| Лист «Лояльность» (подарок/кэшбэк) | `ui/MainActivity.kt:2840-2847`, `2934-2936` |
| Применение скидки + extras | `integration/DiscountIntegrationService.kt:26-70` |
| Очередь и номера | `data/OrderQueue.kt` (`OrderStatus`, `QueueOrder`, `OrderNumbers`), `data/OrderQueueStore.kt` |
| Подтверждение оплаты | `ui/MainActivity.kt:4205-4240` (`onResume`), `3038-3055` |

### Как рисуется прогресс (факт)

```kotlin
val goal = card.cupsForFree.coerceAtLeast(1)
val filled = card.paidCups.coerceIn(0, goal)
stat("ЧАШКИ", "$filled / $goal", true)          // MainActivity.kt:487-489
...
text = if (card.freeAvailable > 0) "Бесплатный напиток доступен в этом чеке"
       else "Ещё $left … до бесплатного напитка"  // :496-501
```

То есть UI печатает `paidCups` как «набрано чашек», а `freeAvailable` — как признак подарка.

### Почему видно «0 из 5» (диагноз)

1. Backend для ручного кода (`reservations.ts:65-76`) отдаёт `paidCups = paid_total % cupsForFree` (остаток цикла) и **принудительно обнуляет** `freeAvailable` и `cashback`. При `paid_total = 5` это `paidCups = 0`, `freeAvailable = 0` → касса честно рисует «0 / 5» и не показывает подарок.
2. APK читает ровно одно имя поля (`paidCups`, `cupsForFree`, `freeAvailable`, `cashback`) — алиасов нет, `paidTotal`/`freeUsed` не читаются вообще, поэтому вычислить реальный прогресс нечем.
3. Семантика не различается: остаток цикла и «сколько набрано» — одно и то же поле, а признак «подарок заработан» на ручном коде недоступен.

### Почему карта «залипает»

`CardSession` — снимок на 10 минут (`CardSession.kt:33`), обновляется только при новом сканировании/вводе. После продажи пере-`resolve` нет, поэтому новые чашки появляются лишь после «отвязать/привязать».

### Что в очереди уже есть

`OrderStatus.PENDING → CONFIRMED → READY` (+ `EXPIRED`), номер присваивается при `confirm` (`OrderQueueStore.confirm`), счётчик сбрасывается по эпохе: номер смены Эвотора, иначе календарные сутки (`OrderNumbers`). Отдельного post-pay экрана нет: после оплаты приложение уходит в `Screen.QUEUE`.

### План серии

C01 прогресс и «подарок» для ручного кода · C02 `CardSession.refreshFromServer` · C03 триггеры refresh · C04 poll 30 с · C05 номер заказа · C06 post-pay UI · C07 версия и отчёт.

## STEP-C01 — P0: прогресс карты не «ноль» (ручной код)

**Что было:** UI печатал `card.paidCups` как «набрано чашек» и включал «подарок доступен» только по `freeAvailable`. Для ручного кода сервер отдаёт `paidCups = paid_total % cupsForFree` (=0 при `paid_total=5`) и обнуляет `freeAvailable`/`cashback` → «0 из 5» и никакого подарка.

**Что сделано**
- `LoyaltyApi.parseResolve(JSONObject, code)` — вынесенный разбор успешного ответа с алиасами: `paidCups|cups|cupsTowardFree|paid`, `cupsForFree|cups_for_free|goal`, `freeAvailable|free_available`, `cashback|cashbackKopecks|cashback_kopecks`, `paidTotal|paid_total`, `freeUsed|free_used`, `reservationId|reservationExpiresAt|seq`.
- `CardInfo.paidTotal` / `CardInfo.freeUsed` — новые необязательные поля, переживают рестарт (`CardSession.set/get`).
- `data/CardProgress.kt` — чистая модель: `cups/goal`, `giftAvailable` (что показать), `giftKnown` (можно ли утверждать), `redeemEnabled` (можно ли списывать), `cashbackKnown`. Правила:
  - `paidTotal` известен → прогресс `paidTotal % goal`, подарки `paidTotal/goal − freeUsed`;
  - иначе — `paidCups` (как есть), подарок считается заработанным, если `freeAvailable>0` или `paidCups ≥ goal`;
  - **`redeemEnabled` только при QR + живой резерв + `freeAvailable>0`** — ручной код никогда.
- UI (`MainActivity`): плашка карты, панель в корзине и строка статуса считают через `CardProgress`; кэшбэк при скрытом значении — «—»; подпись `cardProgressLine()`:
  - QR с резервом → «Бесплатный напиток доступен в этом чеке»;
  - подарок, но ручной код → «Подарок доступен · списание по QR-карте»;
  - данных нет (ручной код) → «Бонусы и подарок — по QR-карте»;
  - иначе → «Ещё N чашек до бесплатного напитка».

**Тесты** (`CardProgressTest`, 10 кейсов): `manual code with five cups shows full progress and gift, but no redeem` (фикстура задачи: paidCups=5 → 5/5, gift=true, redeem=false), `token with a live reservation can redeem`, `token without reservation cannot redeem`, `expired reservation does not allow redeem`, `true zero stays zero`, `paidTotal gives exact progress…`, `paidTotal with unused gift reports an available gift`, `known aliases are parsed`, `cashback is marked unknown for a redacted manual response`, `progress survives the session round trip`.

**Прогон:** `:app:testDebugUnitTest` — **149 тестов, 0 падений**.

**Ограничение (в отчёт):** при текущем backend ручной код не даёт ни `paidTotal`, ни `freeAvailable`, поэтому «5/5 + подарок» для него показывается только если сервер пришлёт `paidCups ≥ goal`/`paidTotal`. Реальные значения требуют 3-строчной правки в `reservations.ts` (info-only поля) — вне правил серии, зафиксировано в отчёте.

## STEP-C02 — `CardSession.refreshFromServer`

- `data/CardRefresh.kt` — чистая логика: `credential(info)` (токен для QR, иначе канонический код), `apply(current, fetched)` → `UPDATED | DROPPED | KEPT_STALE`, `isNotable(...)` для тоста.
- `CardSession.refreshFromServer(ctx, fetch)` — запрос тем же credential, затем: `UPDATED` → заменить сессию и снять пометку «устарело»; `DROPPED` (сервер отказал) → `clear`; `KEPT_STALE` (сеть/5xx) → оставить данные и выставить флаг (`CardSession.isStale`).
- `fetch` передаётся параметром — сеть не нужна для тестов.

**Тесты:** `CardRefreshTest` 7/7 (токен/код как credential, успех, отказ, офлайн, «что считать изменением»).

## STEP-C03 — Триггеры обновления

`refreshCardAsync(notifyOnChange)` в `MainActivity` (фон, защита от параллельных вызовов):

1. **После продажи** — сразу после `confirmPendingOrder()` и перехода в `Screen.QUEUE`.
2. **Кнопка «Обновить»** в панели карты в корзине (рядом с кодом; при устаревших данных подпись «Обновить ·»). Меняет экран только при заметном изменении и показывает баннер „Карта обновлена…“.
3. **Открытие карты гостя** — один запрос в `renderCard()`.

Исходы: `UPDATED` → перерисовать; `DROPPED` → баннер «Карта больше недоступна. Привяжите карту заново.»; `KEPT_STALE` → оставить данные (флаг `CardSession.isStale`).

**Важно:** сразу после продажи backend ещё не обработал SELL (poll раз в минуту), поэтому первое обновление может вернуть старые чашки — актуальные придут следующим poll (C04). Это ожидаемо, не баг.

**Прогон:** `:app:testDebugUnitTest` — 156 тестов, 0 падений.

## STEP-C04 — Poll 30 с, пока карта привязана и экран виден

- `data/CardPoll.kt` — гейт `shouldRefresh(hasCard, visible, lastAtMs, nowMs)`, `INTERVAL_MS = 30_000`.
- `MainActivity`: `cardPollTick` (Handler на main looper) запускается в `onResume`, останавливается в `onPause` и сам гаснет, если карта отвязана. Опрос идёт через тот же `refreshCardAsync(notifyOnChange = true)` — баннер только при заметном изменении (`CardRefresh.isNotable`).

**Тесты:** `CardPollTest` 5/5 (первый тик, нет карты, экран скрыт, интервал, «не 2 секунды»).
**Прогон:** 161 тест, 0 падений.
