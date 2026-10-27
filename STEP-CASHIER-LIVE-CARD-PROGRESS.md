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
