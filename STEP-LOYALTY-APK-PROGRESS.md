# STEP-LOYALTY-APK-PROGRESS

Серия: LOYALTY APK L-00 (`PROMPTS-LOYALTY-APK-L00-SEQUENTIAL.md`).
Источник находки: `RESEARCH-LOYALTY-AUDIT-67-FINAL.md` §1, §11, §13 (L-00), backlog #0.

## STEP-A00 — Baseline (2026-10-27)

| Поле | Значение |
|---|---|
| Репозиторий | `evotor-app` (`~/Documents/evotor-app`) |
| HEAD | `216bf88` — `docs(release): signing key info` |
| Ветка | `main` |
| `versionCode` (в файле) | **118** |
| `versionName` (в файле) | **5.1.3** |
| `applicationId` | `ru.sixthcup.evotor` |
| Рабочее дерево | `M app/build.gradle.kts` (незакоммиченный подъём 117→118 из предыдущей задачи), `?? .vscode/` (не моё, не трогаю) |

### Замечание по версии

`117 / 5.1.2` — последняя версия в git. `118 / 5.1.3` существует только как незакоммиченная правка, но **APK с versionCode 118 уже собирался** (в предыдущей задаче, с кодом до исправления L-00). Чтобы «один versionCode = одно состояние кода», серия заканчивается на **119**, а не 118. Переход 117→119 в истории git объясняется этим.

### Ключевые файлы (найдены, пути фактические)

| Что | Файл |
|---|---|
| Модель сессии карты | `app/src/main/java/ru/sixthcup/evotor/data/CardSession.kt` |
| Сборка extras/sc | `app/src/main/java/ru/sixthcup/evotor/data/CardSession.kt:73-93` |
| Тест extras | `app/src/test/java/ru/sixthcup/evotor/data/CardSessionExtrasTest.kt` |
| Расчёт скидки по резерву | `app/src/main/java/ru/sixthcup/evotor/data/DiscountCalc.kt:17-45` |
| Запрос resolve + маппинг ошибок | `app/src/main/java/ru/sixthcup/evotor/net/LoyaltyApi.kt:17-75` |
| Применение скидки и запись extras | `app/src/main/java/ru/sixthcup/evotor/integration/DiscountIntegrationService.kt:26-70` |
| Экран карты, лист «Лояльность» | `app/src/main/java/ru/sixthcup/evotor/ui/MainActivity.kt:620-653, 2840-2847, 2934-2936` |

### Текущее поведение (одна фраза)

При успешном `resolve` по QR APK кладёт в `extras.sc.c` **числовой `cardCode`**, а не подписанный токен, поэтому backend идёт по «ручной код»-ветке, не вызывает `consumeReservation`, и `free/cb` не списываются при уже выданной на кассе скидке; ручной ввод кода даёт то же значение `c`, что и раньше.

### Цитата текущего builder'а extras (CardSession.kt:73-93)

```kotlin
    fun extras(info: CardInfo, freeApplied: Boolean, cashbackKopecks: Long): JSONObject {
        val sc = sc(info, freeApplied, cashbackKopecks)
        // Плоский ключ нужен текущему парсеру: если appId не совпал с EVOTOR_APP_ID на backend,
        // льгота не потеряется. Оба значения одинаковые — двойного списания нет.
        return JSONObject()
            .put(BuildConfig.APP_UUID, JSONObject().put("sc", sc))
            .put("sc", sc)
    }

    /** extras.sc written into the receipt. The backend re-checks everything when it reads the SELL. */
    fun sc(info: CardInfo, freeApplied: Boolean, cashbackKopecks: Long): JSONObject {
        val card = info.cardCode.ifBlank { info.code }
        val o = JSONObject()
            .put("v", 2)
            .put("c", card)
            .put("ts", System.currentTimeMillis())
            .put("kind", kindOf(info.code))
        if (info.reservationId != null) o.put("op", info.reservationId)
        o.put("free", if (freeApplied) 1 else 0).put("cb", cashbackKopecks)
        return o
    }
```

Регрессия: коммит `57a5c5f` заменил `put("c", info.code)` (сырое значение; для QR — подписанный токен) на `cardCode.ifBlank { code }`.

### Наблюдения для следующих шагов

- `CardInfo.code` — ровно «то, что показал гость»: для QR это подписанный токен, для ручного ввода — цифры. Отдельного поля под токен нет, поэтому A01 восстанавливает `info.code` как источник `sc.c`, а не вводит новое поле.
- `CardSessionExtrasTest` сейчас **закрепляет баг** («`c` == cardCode», «токен только если cardCode пуст») — в A01 тест переписывается; в отчёте фиксируется, что он был частью регрессии.
- `sc.op` уже пишется при `reservationId != null` — A02 проверяет и закрепляет тестом.
- `sc.q` не пишется и не приходит от сервера; A03 делает поддержку опциональной (ключ только при известном seq).
- `freeStatus = ALREADY_RESERVED` сейчас **никак не показывается** кассиру (обрабатывается только `OFFLINE`/`ERROR`) — это A04.

## STEP-A01 — P0: `sc.c` = signed token при QR (L-00)

**Before** (`CardSession.kt`, было):

```kotlin
fun sc(info: CardInfo, freeApplied: Boolean, cashbackKopecks: Long): JSONObject {
    val card = info.cardCode.ifBlank { info.code }
    val o = JSONObject()
        .put("v", 2)
        .put("c", card)
        .put("ts", System.currentTimeMillis())
        .put("kind", kindOf(info.code))
    if (info.reservationId != null) o.put("op", info.reservationId)
    o.put("free", if (freeApplied) 1 else 0).put("cb", cashbackKopecks)
    return o
}
```

**After** (`CardSession.kt:82-103`):

```kotlin
fun sc(info: CardInfo, freeApplied: Boolean, cashbackKopecks: Long): JSONObject {
    val c = scValue(info)
    val o = JSONObject()
        .put("v", 2)
        .put("c", c)
        .put("ts", System.currentTimeMillis())
        .put("kind", kindOf(c))
    if (info.reservationId != null) o.put("op", info.reservationId)
    o.put("free", if (freeApplied) 1 else 0).put("cb", cashbackKopecks)
    return o
}

/**
 * QR: подписанный серверный токен ... Ручной ввод: канонический цифровой код из resolve.
 */
internal fun scValue(info: CardInfo): String =
    if (kindOf(info.code) == "token") info.code.trim()
    else info.cardCode.ifBlank { info.code }.trim()
```

Почему без нового поля: `CardInfo.code` уже хранит ровно «то, что показал гость» (для QR — подписанный токен), и переживает рестарт (`set`/`get`). Отдельное поле токена дублировало бы его.

`kind` теперь вычисляется от значения, реально попавшего в `c`, а не от исходного скана — иначе метка «token» могла стоять при числовом `c` (это и было в баге).

**Тесты** (`CardSessionExtrasTest`, 6 кейсов, переписаны — прежний файл закреплял баг):
`qr session puts the signed token in sc c, not the card code`, `manual card code stays numeric and never pretends to be a token`,
`regression - token in session is never replaced by card code`, `no op key without an active reservation`,
`offline qr without resolve still sends the token`, `extras nested under app uuid and flat sc are identical`.

**Доказательство, что тесты ловят баг:** временно вернул старую строку → `6 tests completed, 2 failed`
(`qr session puts the signed token…`, `regression - token in session…`); после восстановления — 6/6 green, файл побайтово тот же (sha256 `11c67389…`).

**Прогон:** `./gradlew :app:testDebugUnitTest --tests ru.sixthcup.evotor.data.CardSessionExtrasTest` → BUILD SUCCESSFUL, tests=6 failures=0.

## STEP-A02 — `sc.op` и согласованность free/cb

**Что уже было:** `reservationId` сохраняется в сессии (`CardSession.set/get`) и писался в `sc.op` при непустом резерве. Проверено: `LoyaltyApi.resolve` берёт `reservationId` из ответа, `DiscountCalc.reserved()` включает скидку только при живом резерве.

**Что добавлено (инвариант):** `free`/`cb` пишутся только вместе с `op`. Без резерва backend применяет `min(0, …) = 0`, поэтому числовые `free=1`/`cb>0` в чеке были ложными (путали разбор и логи).

```kotlin
val reserved = info.reservationId != null
if (reserved) o.put("op", info.reservationId)
o.put("free", if (reserved && freeApplied) 1 else 0)
    .put("cb", if (reserved) cashbackKopecks.coerceAtLeast(0L) else 0L)
```

**Тесты:** `reservation id is written as op and kept in both extras forms`, `benefits are only claimed together with the reservation id`.
**Прогон:** 8/8 green.

**JSON-ключи extras (итог):** `v`, `c`, `ts`, `kind`, `op` (только с резервом), `free`, `cb`; дублируются под `BuildConfig.APP_UUID` и плоско.

## STEP-A03 — `sc.q` optional (N-01 / L-04)

`RESEARCH §11`: APK вообще не отправлял `sc.q`, backend `sequenceMatches` захардкожен `true`. Обе стороны менять не договаривались, поэтому:

- `CardInfo.seq: Int?` — новое **необязательное** поле; читается из ответа resolve, только если сервер его пришлёт (`LoyaltyApi.kt`), и переживает рестарт (`CardSession.set/get`).
- `sc.q` пишется, только если `seq > 0` (`CardSession.sc`). Нет seq — ключа нет, чек пробивается как раньше, никакой блокировки.
- Backend на HEAD seq не отдаёт, поэтому фактический payload не изменился; шаг готовит совместимость.

**Тесты:** `sc q is omitted when the server did not send a sequence`, `sc q is passed through when the sequence is known`, `zero sequence is treated as unknown`.
**Прогон:** extras 11/11; полный `:app:testDebugUnitTest` — 132 теста, 0 падений.

## STEP-A04 — Понятные сообщения кассиру (L-21 + ALREADY_RESERVED)

Backend отдаёт `{ "error": "<code>" }` (`app.ts:61`), коды: `invalid_card`, `card_not_found`, `card_blocked` (404), `proxy_not_configured` (503). Раньше APK схлопывал всё в «Карта не найдена…» / «Сервер временно недоступен».

Добавлено (без редизайна UI):

| Условие | Текст |
|---|---|
| `card_blocked` | «Карта заблокирована.» |
| `card_not_found`, `invalid_card` | «Карта не найдена. Проверьте номер или отсканируйте QR.» |
| `proxy_not_configured` | «Сервер лояльности не настроен. Обратитесь к администратору.» |
| 400/404 без кода | прежний «Карта не найдена или недействительна…» |
| 401/403 | без изменений |
| ≥500 без кода | прежний «Сервер лояльности временно недоступен (N).» |
| `freeStatus = ALREADY_RESERVED` | «Карта уже зарезервирована на другой кассе. Завершите там чек или подождите.» — баннером WARN на экране заказа (раньше не показывалось вовсе) |

Логика маппинга вынесена в чистые `LoyaltyApi.resolveErrorText` / `resolveDrop`, чтобы покрыть тестом без сети. `drop` по-прежнему только 400/404: на 503 гость не должен терять начисление чашек.

**Тесты:** новый `LoyaltyApiErrorTextTest` — 7/7. Полный прогон: 139 тестов, 0 падений.
