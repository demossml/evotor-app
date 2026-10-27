# IMPLEMENTATION — LOYALTY APK L-00 (evotor-app)

**Дата:** 2026-10-27
**Репозиторий:** `evotor-app` (`~/Documents/evotor-app`), ветка `main`
**База серии:** `216bf88` → итог **`5.1.4` / versionCode `119`**
**Источник:** `RESEARCH-LOYALTY-AUDIT-67-FINAL.md` §1, §11, §13 (L-00), §15 backlog #0; план — `PROMPTS-LOYALTY-APK-L00-SEQUENTIAL.md`.

## Что исправлено

**L-00 (P0).** Касса отправляла в `extras.sc.c` числовой `cardCode`, а backend по форме значения
выбирает ветку: токен (с `.`) → `verifyServerToken` + `consumeReservation`; цифры → «ручной код»,
списание = 0. Из-за этого скидка по резерву выдавалась на кассе, но `cards.free_used` и
`users.cashback_balance` не уменьшались, а резерв оставался `RESERVED` и переиспользовался.

Регрессия появилась в `57a5c5f` (versionCode 114) вместе с удалением экрана оплаты: строка
`put("c", info.code)` превратилась в `cardCode.ifBlank { code }`.

Теперь: `CardSession.scValue()` → для QR подписанный токен, для ручного ввода канонический
цифровой код из `resolve`. `kind` вычисляется от фактически отправленного значения.

## Коммиты серии

| SHA | Сообщение |
|---|---|
| `061753a` | `docs(loyalty): APK L-00 series baseline` |
| `d02da50` | `fix(loyalty): put signed QR token in extras.sc.c not cardCode (L-00)` |
| `0ad6cd5` | `fix(loyalty): include reservation id in sc.op when reserved` |
| `edd03af` | `feat(loyalty): pass sc.q when known` |
| `9e08aa3` | `fix(loyalty): clearer cashier messages for reserve and proxy errors` |
| `4ab7f46` | `docs: QR signature verified on server not in APK` |
| … | `chore(apk): bump version after loyalty L-00 extras fix` |
| … | `docs(loyalty): APK L-00 implementation report` |

Порядок и содержимое каждого шага — в `STEP-LOYALTY-APK-PROGRESS.md` (включает before/after и
доказательство, что новые тесты падают на старом коде).

## Изменённые файлы

**Код**
- `app/src/main/java/ru/sixthcup/evotor/data/CardSession.kt` — `scValue()`, инвариант `free/cb` только с `op`, опциональный `seq`/`sc.q`, документация полей
- `app/src/main/java/ru/sixthcup/evotor/net/LoyaltyApi.kt` — чтение `seq`, чистые `resolveErrorText()` / `resolveDrop()`
- `app/src/main/java/ru/sixthcup/evotor/ui/MainActivity.kt` — баннер `ALREADY_RESERVED`
- `app/build.gradle.kts` — 5.1.4 / 119

**Тесты**
- `app/src/test/java/ru/sixthcup/evotor/data/CardSessionExtrasTest.kt` — переписан (11 кейсов; прежняя версия закрепляла баг)
- `app/src/test/java/ru/sixthcup/evotor/net/LoyaltyApiErrorTextTest.kt` — новый (7 кейсов)

**Документы**
- `docs/LOYALTY-EXTRAS-CONTRACT.md` — новый
- `docs/CASHIER-APP.md` — п.7: подпись проверяет сервер + ссылка
- `STEP-LOYALTY-APK-PROGRESS.md` — журнал серии

Backend `sixth-cup` **не менялся**.

## Результаты проверок

| Проверка | Команда | Результат |
|---|---|---|
| Unit-тесты | `./gradlew :app:testDebugUnitTest` | **139 тестов, 0 падений, 0 ошибок** (до серии 124) |
| Lint | `./gradlew :app:lintDebug` | **0 errors, 111 warnings** (baseline без изменений) |
| Release | `./gradlew assembleRelease` | BUILD SUCCESSFUL |

**Артефакт:** `app/build/outputs/apk/release/app-release.apk` — 5 675 290 байт,
`versionCode=119`, `versionName=5.1.4`, подпись постоянным ключом
`CN=6.7 Coffee, OU=POS, O=6.7 Coffee, L=Yekaterinburg, C=RU`
(`SHA-256 b51713e9…c1a7d`, см. `docs/RELEASE-KEY.md`).

## Что проверено и как

- Списание запускается только при токене в `c` — это код backend (`SellHandler.ts:150,205`), поэтому проверка APK-стороны сведена к «что именно попадает в `c`»: 11 тестов на `CardSession`, включая явную регрессию «токен не подменяется кодом».
- Доказательство, что тесты ловят баг: временный возврат старой строки → 2 падения (`qr session puts the signed token…`, `regression - token in session…`), затем восстановление файла побайтово (sha256 `11c67389…`).
- Ручной код остаётся «начисление без списания» — тест фиксирует `c` = цифры и `kind = code`.
- `free/cb` больше не пишутся без `op`: без резерва backend всё равно применяет 0.
- `sc.q` появляется только при известном seq; backend его пока не отдаёт, поэтому фактический payload не изменился.

## Остаточный риск

1. **E2E на живой кассе не выполнялся.** Проверка «после SELL `cards.free_used` вырос, `loyalty_ops.free=1`, резерв `CONSUMED`» возможна только после установки APK и прохождения чека через Evotor poll.
2. **Списание free/cb на сервере проверяется после выкладки APK и poll.** APK-часть подтверждена тестами; серверная — кодом backend и существующими тестами `reservations.test.ts`, но не совместным прогоном.
3. **Старый APK в поле** продолжает отправлять `cardCode` — до обновления касс на 5.1.4 дефект сохраняется.
4. **`sixth-cup/ANDROID_INTEGRATION_CONTRACT.md:79-83`** всё ещё утверждает, что подпись QR проверяет APK. Правка требует отдельного разрешения на backend-репозиторий.
5. **Backend seq не отдаётся** — `sc.q` фактически не отправляется; `sequenceMatches = true` на сервере остаётся (L-04 не закрыт, только подготовлен).
6. **Установка на терминал** требует удаления предыдущей сборки только если сменился ключ; текущая подпись совпадает с `117`, поэтому обновление ставится поверх.
7. В `sixth-cup` на момент работы появилась **чужая** незакоммиченная правка `backend/src/modules/loyalty/receipts.ts` (атомарное погашение ваучера, L-06) и HEAD сдвинулся на `54d37a3` — к этой серии отношения не имеет, не трогал.

## Не делалось

- Backend `sixth-cup` не изменялся, push не выполнялся (разрешения на remote не было), версия кассы на устройство не устанавливалась, рабочая БД не затрагивалась.
