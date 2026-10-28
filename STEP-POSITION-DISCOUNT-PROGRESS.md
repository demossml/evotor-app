# STEP-POSITION-DISCOUNT-PROGRESS

Промпт: `PROMPTS-CASHIER-POSITION-DISCOUNT-SEQUENTIAL.md` · P00 → P07
Основание: `RESEARCH-EVOTOR-FISCAL-DISCOUNT.md` (путь позиции, а не авто-`receiptDiscount`)

## P00 — Baseline

| | |
|---|---|
| HEAD на старте | `c5bb8f9` (5.2.7-prompt-once, 132) |
| Тесты на старте | 250 / 0 |
| `setPriceWithDiscountPosition` в коде | **не было** |

Проверено `javap` по `integration-library-0.6.27`:
`ru.evotor.framework.receipt.Position$Builder.setPriceWithDiscountPosition(BigDecimal)` существует,
`Position.getPriceWithDiscountPosition()` и `getDiscountPositionSum()` тоже.

Схема до фикса:

```
Cart → SellLauncher: Position(price = полная, quantity)  → OpenSellReceiptCommand
                                   ↓
                    DiscountIntegrationService: ReceiptDiscountEventResult(discount)  ← срабатывает не всегда
```

## P01 — Расклад «позиции / чек»

`data/FiscalDiscount.kt`:

- `FiscalCalc.split(gross, eligible, card, choice, now) → FiscalSplit(positionDiscountKopecks,
  receiptDiscountKopecks, giftApplied, voucherId, floorApplied)`;
- подарок = скидка **одной** единицы до ровно `1.00 ₽`; ваучер — на остаток; кэшбэк остаётся
  скидкой на чек; страховка «на чеке минимум 1 ₽»;
- `FiscalLines.build(lines, split)` раскладывает скидку по единицам и разворачивает обратно
  в позиции: подарочная единица — отдельной строкой, остальные — по полной цене.

## P02 — `SellLauncher`

- Позиции собираются из `FiscalLines`, а не из `Cart` напрямую.
- Для строки со скидкой вызывается `builder.setPriceWithDiscountPosition(1.00)` (только если
  цена со скидкой > 0 и меньше полной).
- Логи: `fiscal_positions discount=… gift=… voucher=… floor=… lines=…` и `fiscal_pos product=… price=… withDiscount=… qty=…`.

## P03 — UI

`LoyaltyPreview` теперь берёт итог из `FiscalCalc.split`: «к оплате» и «скидка» совпадают с суммой
фискальных линий по построению. Подпись подарка: «Подарок −179 ₽ (в чеке 1 ₽)».

## P04 — Сервис без двойной скидки

Выбран режим **не дублировать** (default промпта): подарок и ваучер уже в ценах позиций,
`DiscountIntegrationService` возвращает только кэшбэк (+ комбо). В лог:
`fiscal_discount … on_positions=… reason=already_on_positions`.

## P05 — Граничные случаи (тесты)

- смешанный чек: подарочная единица 1 ₽ + остальные по полной;
- две единицы одного напитка: скидка только на одну;
- добавка (`optionId != null`) никогда не становится подарком;
- ваучер 15% распределяется по позициям, сумма = UI;
- ваучер 100% → на чеке 1 ₽, нулевых цен нет;
- ручной код без резерва → цен не меняем.

## P06 — Prompt льготы один раз

**skip, already once** — сделано в `PROMPT-APK-FIX-BENEFIT-PROMPT-ONCE` (`BenefitPromptGuard`, коммит `c5bb8f9`).

## P07 — Версия

133 / `5.2.8-position-discount`, тесты 263 / 0, lint без ошибок, release APK собран.
