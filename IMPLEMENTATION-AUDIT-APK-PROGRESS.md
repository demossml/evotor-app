# IMPLEMENTATION — APK audit fixes (evotor-app)

Источник: `2n.md` (PLAN-AUDIT-FIXES-67-ALL) + промпт волн. Backend/PWA (`sixth-cup`) — вне этого репозитория.

## WAVE 0 — baseline (W0-1)

| | |
|---|---|
| HEAD | `6e6329c` (docs(preorder): 163 report) |
| versionCode / versionName | `163` / `5.3.4-preorder-benefit` |
| `./gradlew :app:testDebugUnitTest` | **560 pass / 0 fail / 0 errors**, 66 классов |
| после волны 1 | **579 pass / 0 fail / 0 errors**, 68 классов; lint 0 errors / 108 warnings |
| рабочее дерево | чисто (кроме untracked `2n.md` — документ аудита, не коммитим) |

### Якоря (только чтение)

| Что | Файл |
|---|---|
| сборка `sc` JSON (`v, c, op, free, cb, ts, kind, ord, qn, preorder`) | `app/src/main/java/ru/sixthcup/evotor/data/CardSession.kt` |
| extras в чек при SELL | `app/src/main/java/ru/sixthcup/evotor/sell/SellLauncher.kt` |
| скидка на чек (сервис Эвотор) | `app/src/main/java/ru/sixthcup/evotor/integration/DiscountIntegrationService.kt` |
| «В чек» по предзаказу | `app/src/main/java/ru/sixthcup/evotor/ui/orders/OrderDetailActivity.kt` (`onPull`), `data/PreorderPull.kt` |
| сессия подтянутого предзаказа | `app/src/main/java/ru/sixthcup/evotor/data/PreorderPullSession.kt` |
| расчёт льготы | `data/DiscountCalc.kt`, `data/FiscalDiscount.kt` (`FiscalCalc`, `FiscalLines`), `data/LoyaltyBenefit.kt` |
| экран «К оплате» | `ui/MainActivity.kt` (`currentBenefits`, `cartTotalRub`) |
| mark-paid | `ui/MainActivity.kt` (`markPreorderPaid`, `onResume` → закрытие чека) |
| сигнал «чек напечатан/оплачен» | `integration/ReceiptPrintExtraService.kt`, `data/SalePrintStore.kt` |
| отмена/аннул | `ui/MainActivity.kt` (`cancelPendingOrder`, ветка `sellOpen == true`) |

## WAVE 1 — P0

| STEP | Статус | Тесты |
|---|---|---|
| P0-3-APK — контракт sc + льгота предзаказа | done | 575 pass / 0 fail (после шага) |
| P0-6-APK — mark-paid / закрытие чека | done | 579 pass / 0 fail (после шага) |
| W1-WARN — предупреждения без uuid | done (лог + диалог уже были) | — |
| W1-BUILD — сборка волны 1 | done | 579 pass / 0 fail, lint 0 errors, APK `dist/sixthcup-evotor-5.3.5-audit-p0-vc164.apk` (sha256 `67ef1e0d…`) |

## WAVE 2 — P1

| STEP | Статус | Тесты |
|---|---|---|
| P1-7 — choice/корица для бариста | done | 583 pass / 0 fail (после шага) |
| P2-5 — не глотать ошибки скидки | done | 583 pass / 0 fail |
| P2-7 — CI | не делал | в репозитории нет CI-заготовок (`.github` отсутствует) |
| WAVE 4 | не делал | вне scope |

### P1-7 — что изменено

- `data/PreorderPull.kt`: `Line.choiceTitle/choiceLabel` из модификатора `groupKey=choice`;
  `choiceOf()` не выдумывает название схемы, если backend прислал в `group` ключ `choice`.
- `ui/orders/OrderDetailActivity.kt`: выбор передаётся в `CartLine` → `ChoicePrintStore` → PrintExtra
  печатает строку бариста. Позиция 0 ₽ не создаётся.

### P2-5 — что изменено

- `data/DiscountFailure.kt` (новый): `report/take`, текст кассиру.
- `integration/DiscountIntegrationService.kt`: `catch` больше не молчит — `Log.e` + `DiscountFailure.report`.
- `ui/MainActivity.kt`: при возврате на главный экран кассир видит «Не удалось применить скидку».
- `data/Prices`/`DiscountCalc.compute` не менялись.

## Финальная сборка

| | |
|---|---|
| версия | **165 / 5.3.6-audit-p1** |
| APK | `dist/sixthcup-evotor-5.3.6-audit-p1-vc165.apk` |
| sha256 | `193cce279e768d041fa7c971cff5f156a56e0439b9734fe60bbe5f16a13cd2c2` |
| тесты | 583 pass / 0 fail / 0 errors, 69 классов |
| lint | 0 errors / 108 warnings |
| отчёт | `IMPLEMENTATION-AUDIT-APK-FINAL-REPORT.md` |

### P0-3 — что изменено

- `data/OrderBenefit.kt` (новый): льгота заказа из `benefit`+`quoteTotal`, восстановление из сессии.
- `data/PreorderPullSession.kt`: хранит `benefit` (kind/title/amountRub) вместе с составом.
- `ui/orders/OrderDetailActivity.kt` (`onPull`): сохраняет льготу в сессию; лог `pull_modifier_no_uuid`.
- `data/FiscalDiscount.kt` (`FiscalCalc.split`): параметр `orderBenefitRub`; при >0 карточные правила
  не считаются, скидка уходит ценами позиций, пол 1 ₽ сохранён.
- `sell/SellLauncher.kt`: передаёт льготу в split; `sc` теперь пишется и **без карты**; при льготе
  заказа `free=0`, `cb=0`, ваучер не отправляется; очередь (`ord`/`qn`) пишется всегда.
- `data/CardSession.kt`: `extras/sc` принимают `CardInfo?`; `preorder` — отдельный ключ,
  `ord` остаётся id очереди.
- `integration/DiscountIntegrationService.kt`: при льготе заказа карточные правила не применяет;
  `sc.preorder` добавляется в оба места записи extras; устаревшая сессия игнорируется, если её
  строк нет в корзине.
- `data/LoyaltyBenefit.kt`: `LoyaltyPreview` учитывает льготу заказа (payable/строки/подпись),
  карточные строки при этом нулевые.
- `ui/MainActivity.kt`: «К оплате» и полоса корзины считают льготу заказа (`activeOrderBenefit`).

### P0-6 — что изменено

- `data/PreorderPaid.kt` (новый): ретрай только сеть/5xx (макс. 3 попытки), `shouldMarkSalePaid`.
- `data/SaleConfirmStore.kt` (новый): маркер «чек продажи напечатан» (TTL 30 мин).
- `integration/ReceiptPrintExtraService.kt`: при печати SELL ставит маркер (до любых ошибок extra).
- `ui/MainActivity.kt`: `markPreorderPaid` — ограниченный ретрай с паузами; `paid` отправляется
  только если напечатан чек продажи (аннул печати не даёт); устаревшая сессия очищается.

### W1-WARN

- Платные опции без `evotorUuid` уже показываются кассиру в диалоге «В чек» и не попадают
  в позиции; добавлен `Log.w pull_modifier_no_uuid` (без имён — только id заказа и счётчик).
