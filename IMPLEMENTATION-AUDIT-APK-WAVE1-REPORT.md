# WAVE 1 — P0 APK: отчёт

Дата: 2026-11-08. Репозиторий: `evotor-app` (касса APK). Backend/PWA `sixth-cup` — не менялся.

## Версии и артефакт

| | |
|---|---|
| было | `163` / `5.3.4-preorder-benefit` |
| стало | **`164` / `5.3.5-audit-p0`** |
| APK | `dist/sixthcup-evotor-5.3.5-audit-p0-vc164.apk` (6 160 349 байт) |
| sha256 | `67ef1e0dc3c9aa974f5ada5f0390fadc366b9d92dbe30757916e55017ead9b4b` |
| badging | `ru.sixthcup.evotor` versionCode 164, versionName 5.3.5-audit-p0, minSdk 23, targetSdk 30 |
| подпись | **debug** (`CN=Android Debug`) — release-keystore на машине нет, см. `docs/RELEASE-KEY.md` |
| финал | волны 1–2 собраны в `165` / `5.3.6-audit-p1` — см. `IMPLEMENTATION-AUDIT-APK-FINAL-REPORT.md` |

## STEP → статус

| STEP | Статус | Что сделано |
|---|---|---|
| W0-1 baseline | done | HEAD `6e6329c`, 163/5.3.4, 560 тестов green |
| **P0-3-APK** | **done** | канон `sc.preorder`, льгота заказа применяется на кассе одним правилом |
| **P0-6-APK** | **done** | `mark-paid` только по напечатанному чеку + ограниченный ретрай |
| W1-WARN | done | платные опции без UUID: диалог кассиру + `Log.w` (счётчик), позиция в чек не уходит |
| W1-BUILD | done | tests + lint + release APK |

## P0-3 — контракт `sc` и льгота предзаказа

### Канон ключей

| Ключ | Значение |
|---|---|
| `sc.preorder` | **id заказа предзаказа (uuid)** — единственный ключ, по которому backend ищет заказ |
| `sc.ord` | id **очереди** (`queue_numbers`), как и раньше; id заказа сюда не попадает |
| `sc.qn` | публичный номер очереди, как и раньше |
| `sc.c`, `sc.op`, `sc.free`, `sc.cb`, `sc.voucherId`, `sc.q`, `sc.kind` | без изменений; при льготе заказа `free=0`, `cb=0`, ваучер не отправляется |

### Льгота заказа на кассе

1. При «В чек» id заказа и `benefit` (`kind`, `title`, `amountRub`) сохраняются в `PreorderPullSession`.
2. Скидка идёт **ценами позиций** (`setPriceWithDiscountPosition`) — этот путь срабатывает на всех
   прошивках; скидка на чек (`receiptDiscount`) для льготы заказа не используется.
3. Льгота заказа — **единственное правило**: карточные подарок/ваучер/кэшбэк в этот чек не считаются
   ни на экране, ни в `DiscountIntegrationService`, ни в `SellLauncher`.
4. «К оплате» в корзине и в листе льгот считает ту же сумму (`LoyaltyPreview` → `FiscalCalc`).
5. Пол 1 ₽ сохранён: чек не может стать нулевым.
6. `sc` пишется **и без карты** (раньше extras вообще не отправлялись без карты).

### Пример `extras.sc` после фикса (карта + предзаказ, льгота заказа)

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
  "sc": { "v": 2, "ts": 1762580000000, "c": "payload.signature", "kind": "token", "op": "res-1", "free": 0, "cb": 0, "ord": "queue-order-uuid", "qn": "47", "preorder": "9f1c2d3e-5a4b-4c3d-8e2f-1a2b3c4d5e6f" }
}
```

Без карты гостя:

```json
{ "sc": { "v": 2, "ts": 1762580000000, "ord": "queue-order-uuid", "qn": "47", "preorder": "9f1c2d3e-5a4b-4c3d-8e2f-1a2b3c4d5e6f" } }
```

### Изменённые файлы P0-3

| Файл | Что |
|---|---|
| `data/OrderBenefit.kt` (новый) | льгота заказа: `from(order)` (нужны `benefit`+`quoteTotal`), `restore` из сессии, подпись |
| `data/PreorderPullSession.kt` | хранение льготы в SharedPreferences вместе с составом |
| `data/FiscalDiscount.kt` | `FiscalCalc.split(..., orderBenefitRub)` — одно правило, пол 1 ₽ |
| `data/CardSession.kt` | `extras/sc` без карты; `preorder` отдельно от `ord` |
| `data/LoyaltyBenefit.kt` | `LoyaltyPreview.of(..., orderBenefit)`, строки/итог для льготы заказа |
| `sell/SellLauncher.kt` | льгота в split; `sc` без карты; `ord`/`qn` всегда; `free/cb=0` при льготе |
| `integration/DiscountIntegrationService.kt` | карточные правила не дублируют льготу заказа; `sc.preorder` не теряется при пересчёте чека |
| `ui/orders/OrderDetailActivity.kt` | сохраняет льготу при «В чек»; `Log.w pull_modifier_no_uuid` |
| `ui/MainActivity.kt` | «к оплате»/полоса корзины учитывают льготу заказа |

## P0-6 — достоверный `mark-paid`

1. **Сигнал оплаты:** `ReceiptPrintExtraService` при печати чека SELL ставит маркер
   `SaleConfirmStore` (id заказа + время, TTL 30 мин). Печать бывает только у фискализированного чека —
   аннулированный/отменённый чек маркера не даёт.
2. `MainActivity` вызывает `mark-paid` только если маркер есть и id совпадает.
   Иначе — `preorder_paid_skipped reason=no_print_signal`, заказ не помечается оплаченным.
3. **Ретрай:** сеть/таймаут/5xx/408/429 — до 3 попыток (паузы 1.5 с и 4 с), 4xx не повторяем.
   `benefit_conflict` — без ретрая, notice кассиру, сессия очищается.
4. **Идемпотентность:** повтор — это тот же `POST /mark-paid`; контракт сервера идемпотентен.
5. Файлы: `data/PreorderPaid.kt` (новый), `data/SaleConfirmStore.kt` (новый),
   `integration/ReceiptPrintExtraService.kt`, `ui/MainActivity.kt`.

## Тесты

`./gradlew :app:testDebugUnitTest` → **579 pass / 0 fail / 0 errors**, 68 классов (было 560 / 66).

| Новое | Тестов | Покрытие |
|---|---|---|
| `OrderBenefitTest` | 6 | `from` (+`quoteTotal`), нормализация kind, нулевая сумма, restore |
| `PreorderPaidTest` | 4 | retryable сеть/5xx/4xx, паузы, гейт «только напечатанный чек» |
| `CardSessionExtrasTest` | +3 | `preorder` ≠ `ord`, `sc` без карты, `free/cb=0` при льготе |
| `FiscalDiscountTest` | +4 | льгота заменяет карточные правила, работает без карты, пол 1 ₽ |
| `LoyaltyBenefitTest` | +2 | payable/подпись льготы, без карты |

`lintDebug` → **0 errors, 108 warnings**.

## Что должен задеплоить backend, чтобы льгота заработала end-to-end

1. Читать **`sc.preorder`** как id заказа (`uuid`). `sc.ord`/`sc.qn` — только очередь, как раньше.
2. Принимать `sc` **без `c`** (продажа по предзаказу без карты гостя).
3. При `sc.preorder` применять/закрывать резерв льготы **только если скидка в чеке согласована**
   (сумма позиций SELL соответствует `quoteTotal`/`estimateTotal − benefit.amountRub`); иначе — dispute,
   резерв не списывать.
4. `mark-paid` — идемпотентный; повторы после сети допустимы.
5. Не путать `ord` (очередь) и `preorder` (заказ) в `bindQueueNumber`.

## ASSUMPTIONS

1. Льгота заказа применяется **одним правилом**: карточные `free`/`cb`/ваучер в чек с предзаказом
   не идут. UI кассы по-прежнему позволяет выбрать карточную льготу, но в расчёт она не попадёт.
2. `benefit` применяется только при наличии `quoteTotal` (иначе правило не подтверждено).
3. Ставка на **позиционную** скидку: на прошивках, где `receiptDiscount` не срабатывает, сумма всё равно
   уходит в чек. Обратная сторона — в чеке это скидка на позиции, а не «скидка на чек» строкой.
4. Сумма скидки ограничена суммой чека с полом 1 ₽; если цена на кассе ниже оценки гостя, скидка
   урезается — в логах `order_benefit rub=…`.
5. Сигналом оплаты считается печать чека SELL (`PrintExtra`). Если прошивка не вызывает PrintExtra,
   `mark-paid` не уйдёт (заказ останется active — это осознанно безопаснее ложного `paid`).
6. Фискальный id/сумма в `mark-paid` не передаются (API их не принимает); сверка — на backend по SELL.
7. Release-подпись недоступна на машине сборки — APK подписан debug-ключом.

## Не менялось

Лояльность карты (QR/resolve), `ReceiptValidator`, `DiscountCalc.compute`, `ReceiptPlan`,
`ChoicePrintStore`, сканер QR карты, `applicationId`, keystore, фискальные 0 ₽ позиции.

## Готово к установке на кассу

**Да, условно:** APK ставится и работает; льгота предзаказа начнёт применяться end-to-end после
деплоя backend-изменений из списка выше. Без них APK продолжает работать по-старому
(`sc.preorder` просто игнорируется).
