# Финальный отчёт APK — исправление аудита (WAVE 1–3)

Дата: 2026-11-08. Репозиторий: `evotor-app`. Backend/PWA `sixth-cup` — не менялся.

## STEP → статус

| STEP | Статус | Комментарий |
|---|---|---|
| W0-1 baseline | **done** | HEAD `6e6329c`, 163/5.3.4, 560 тестов green, якоря выписаны в `IMPLEMENTATION-AUDIT-APK-PROGRESS.md` |
| **P0-3-APK** — контракт `sc` + льгота предзаказа | **done** | `sc.preorder` = id заказа; `ord`/`qn` — только очередь; льгота заказа применяется на кассе одним правилом |
| **P0-6-APK** — mark-paid / закрытие чека | **done** | `mark-paid` только по напечатанному чеку + ретрай сети/5xx (3 попытки); аннул не даёт `paid` |
| W1-WARN — опции без UUID | **done** | кассир видит их в диалоге «В чек», позиция в чек не уходит, `Log.w pull_modifier_no_uuid` (id + счётчик) |
| W1-BUILD — сборка волны 1 | **done** | checkpoint `164` / `5.3.5-audit-p0` |
| **P1-7** — choice/корица для бариста | **done** | выбор из `modifiers[groupKey=choice]` едет в `CartLine.choiceTitle/choiceLabel` → `ChoicePrintStore` → PrintExtra; позиций 0 ₽ нет |
| **P2-5** — не глотать ошибки скидки | **done** | `DiscountIntegrationService` пишет `Log.e` + `DiscountFailure`; кассир видит «Позовите администратора» на главном экране |
| P2-7 — CI | **не делал** | в репозитории нет ни `.github/workflows`, ни иных CI-заготовок — с нуля не разворачивал (по правилу промпта) |
| WAVE 4 | не делал | вне scope |

## Версии и артефакт

| | |
|---|---|
| было (до волны 1) | `163` / `5.3.4-preorder-benefit` |
| checkpoint волны 1 | `164` / `5.3.5-audit-p0` — `dist/sixthcup-evotor-5.3.5-audit-p0-vc164.apk` |
| **финал** | **`165` / `5.3.6-audit-p1`** |
| APK | `dist/sixthcup-evotor-5.3.6-audit-p1-vc165.apk` (6 161 966 байт) |
| sha256 | `597aa899e99ea458e08ed7383f71b2a793f1a8289b4c8ac2338f1237f8daf235` |
| badging | `ru.sixthcup.evotor`, versionCode 165, versionName 5.3.6-audit-p1, minSdk 23, targetSdk 30 |
| подпись | **debug** (`CN=Android Debug`): release-keystore на машине сборки нет (см. `docs/RELEASE-KEY.md`) |
| тесты | **586 pass / 0 fail / 0 errors**, 69 классов |
| lint | **0 errors, 108 warnings** |

## Точный JSON `sc` после фикса

Карта гостя + предзаказ + льгота заказа (позиционная скидка):

```json
{
  "151071e8-88a4-44f6-b71a-b17c559f9b7d": {
    "sc": {
      "v": 2,
      "ts": 1762580000000,
      "c": "payload.signature",
      "kind": "token",
      "op": "res-1",
      "free": 0,
      "cb": 0,
      "ord": "queue-order-uuid",
      "qn": "47",
      "preorder": "9f1c2d3e-5a4b-4c3d-8e2f-1a2b3c4d5e6f"
    }
  },
  "sc": {
    "v": 2,
    "ts": 1762580000000,
    "c": "payload.signature",
    "kind": "token",
    "op": "res-1",
    "free": 0,
    "cb": 0,
    "ord": "queue-order-uuid",
    "qn": "47",
    "preorder": "9f1c2d3e-5a4b-4c3d-8e2f-1a2b3c4d5e6f"
  }
}
```

Без карты (продажа только по предзаказу):

```json
{ "sc": { "v": 2, "ts": 1762580000000, "ord": "queue-order-uuid", "qn": "47", "preorder": "9f1c2d3e-5a4b-4c3d-8e2f-1a2b3c4d5e6f" } }
```

Ключи: `v` — версия контракта ≥2; `c`/`kind` — карта (нет без карты); `op` — резерв карты;
`free`/`cb` — карточные льготы (при льготе заказа всегда `0`); `ord`/`qn` — очередь;
`preorder` — **id заказа предзаказа (uuid)**.

## Что должен задеплоить backend, чтобы льгота заработала end-to-end

1. `SellHandler` читает **`sc.preorder`** как id заказа; `sc.ord` остаётся id очереди (`bindQueueNumber`).
2. Принимать `sc` **без `c`** (предзаказ без карты гостя).
3. Льготу заказа применять/закрывать **только при согласованной скидке** в SELL
   (сумма позиций соответствует `quoteTotal`), иначе — dispute, резерв не списывать.
4. `mark-paid` — идемпотентный (APK повторяет до 3 раз при сети/5xx).
5. `sc.free`/`sc.cb` при непустом `preorder` не трактовать как карточное списание.

## Тесты (полный прогон)

`./gradlew :app:testDebugUnitTest` → **586 / 0 fail / 0 errors** (69 классов; на baseline было 560).

| Файл тестов | Новых | Что проверяет |
|---|---|---|
| `OrderBenefitTest` | 6 | льгота только при `benefit`+`quoteTotal`, restore, подпись |
| `PreorderPaidTest` | 4 | ретрай сеть/5xx, 4xx финальный, гейт «печать чека» |
| `DiscountFailureTest` | 2 | текст кассиру при сбое скидки |
| `CardSessionExtrasTest` | +3 | `preorder` ≠ `ord`, `sc` без карты, `free/cb=0` |
| `FiscalDiscountTest` | +6 | льгота заказа заменяет карточные правила; пол 1 ₽ **на каждую единицу** (0 ₽ позиций нет) |
| `LoyaltyBenefitTest` | +3 | payable/подпись льготы заказа; экран == чек при урезанной скидке |
| `PreorderPullTest` | +5 | choice не в чеке, label для печати, groupKey-группировка |

## ASSUMPTIONS

1. Льгота заказа — **одно правило за раз**: при активном `preorder` карточные подарок/ваучер/кэшбэк
   не применяются и не отправляются (`free=0`, `cb=0`, без `voucherId`). UI пока позволяет выбрать
   карточную льготу, но в расчёт она не попадёт.
2. `benefit` применяется только при наличии `quoteTotal` — без итога правило не подтверждено.
3. Скидка льготы заказа уходит **ценами позиций** (`priceWithDiscountPosition`), т.к. `receiptDiscount`
   срабатывает не на всех прошивках. Следствие: в чеке это скидка на позиции, а не строка «скидка на чек».
4. Сумма скидки ограничена суммой чека; каждая позиция остаётся минимум за 1 ₽ (0 ₽ в чек не пробиваем).
   Если цена на кассе ниже оценки гостя или скидка почти равна сумме чека — применяется на 1–2 ₽ меньше,
   это видно в логах `order_benefit`/`discount_shortfall`, и **экран считает ту же сумму**, что уйдёт в чек.
5. Признак успешной оплаты — печать чека SELL (`PrintExtra`). Если прошивка не вызывает PrintExtra,
   `mark-paid` не уйдёт: заказ останется active. Это осознанно безопаснее ложного `paid`.
6. Фискальный id/сумма в `mark-paid` не передаются (API их не принимает) — сверка на backend по SELL.
7. `choice` из предзаказа печатается через `ChoicePrintStore` (PrintExtra), позиция 0 ₽ не создаётся.
8. Release-подпись недоступна на машине сборки — APK подписан debug-ключом.
9. CI в репозитории отсутствует — workflow не добавлял.

## Не менялось

Лояльность карты (QR/resolve, `CardSession` карточный путь), `ReceiptValidator`, `DiscountCalc.compute`,
`ReceiptPlan`, сканер QR карты, `applicationId`, keystore, фискальные 0 ₽ позиции, `ChoicePrintStore`-механика.

## Готово к установке на кассу

**Да, условно.** APK `165` устанавливается и работает: контракт `sc` приведён к канону, льгота заказа
считается на кассе, `mark-paid` не врёт при аннуле. Полный эффект льготы (списание резерва) появится
после деплоя backend-пунктов 1–5 выше; до него APK работает по-старому (`sc.preorder` игнорируется).

Ограничение: артефакт подписан debug-ключом. Для выкладки в кабинет Эвотор пересобрать
`assembleRelease` с `KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`.
