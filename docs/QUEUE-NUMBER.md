# Публичный номер заказа на чеке (Queue Number)

Короткий номер для гостя и бариста: печатается на чеке продажи вместе с фискальным блоком.
**Не фискальный реквизит** и никогда не подменяет номер ФД, номер чека или UUID документа Эвотора.

## Что зафиксировано

| Параметр | Значение |
|---|---|
| Формат | 1–3 цифры без ведущих нулей: `47`, `103` |
| Максимум | `999`; дальше счётчик идёт по кругу, пропуская активные номера |
| Правило сброса | **календарный день точки** (`period_key = YYYY-MM-DD`, локальная дата сервера) |
| Кто выдаёт | **только backend** (`queue_numbers.ts`); локальные счётчики терминалов запрещены |
| Место печати | `PrintExtraPlacePrintGroupTop` (после клише, до «Кассовый чек»), текст `ЗАКАЗ № 47` |
| Возврат (payback) | номер **не** печатается |
| Backend недоступен | чек печатается **без** номера, продажа и фискализация не блокируются |

## Backend

- Миграция `035_queue_numbers.sql`: `queue_numbers` (по строке на заказ, `UNIQUE(store_uuid, period_key, number)`) и `queue_counters` (атомарный инкремент через `INSERT … ON CONFLICT … RETURNING`).
- `modules/queue/numbers.ts`: `issueQueueNumber`, `bindQueueNumber`, `cancelQueueNumber`, `listQueueNumbers`, `periodKeyOf`, `formatQueueNumber`.
- `POST /api/devices/queue-number` (тот же контур, что `loyalty/resolve`: прокси-токен Эвотора + `X-Evotor-Store-Uuid`):
  запрос `{ orderId?, storeUuid? }` → `{ ok, orderId, publicOrderNumber, periodKey, expiresAt }`.
  Идемпотентно по `orderId`: повтор с тем же id возвращает тот же номер и не тратит новый.
- `GET /api/admin/queue-numbers?storeUuid=…&period=YYYY-MM-DD` — активные номера для поддержки.

Привязка к документу Эвотора — `bindQueueNumber(orderId, { evotorDocumentUuid, fiscalDocumentNumber })`;
номер при этом не меняется. Автоматический вызов из poll **не включён**: нужен идентификатор
заказа в документе Эвотора (см. «Не закрыто»).

## APK

- `net/QueueNumberApi.kt` — запрос номера (таймауты 3/5 с, ошибки не всплывают в UI).
- `data/QueueNumber.kt` + `QueueNumberStore` — номер «под текущую продажу» в SharedPreferences,
  переживает перезапуск; пригоден не дольше 30 минут и до `expiresAt`.
- Запрос выполняется **до** открытия чека (`sendReceipt` → `requestQueueNumber()`), потому что
  PrintExtra срабатывает в момент печати, уже после оплаты.
- `integration/ReceiptPrintExtraService.kt` — `IntegrationService` с процессором
  `evo.v2.receipt.sell.printExtra.REQUIRED`, разрешение `ru.evotor.permission.receipt.printExtra.SET`.
  Группа печати берётся из текущего чека (`Receipt.printDocuments[].printGroup.identifier`),
  иначе `PrintGroup.DEFAULT`.
- Очередь и post-pay используют серверный номер (`OrderQueueStore.confirm(..., publicNumber = …)`;
  локальный счётчик остаётся только аварийным fallback при недоступном backend).
- Любая ошибка печати или отсутствие номера — запись в лог, чек не страдает.

## Проверено тестами

- backend `queue-numbers.test.ts` (10): последовательность в точке, независимость точек, сброс на новый день,
  идемпотентность по `orderId`, пропуск активных номеров, переполнение за 999, отмена, привязка документа,
  номер ≠ номер ФД, HTTP-контур (токен/точка/строка в ответе).
- APK `QueueNumberTest` (5): текст печати, разбор ответа, отбраковка мусора, срок жизни номера,
  приоритет серверного номера при подтверждении.

## Не закрыто (требует решения или устройства)

1. **Привязка к документу Эвотора из poll.** В документе нет нашего `orderId`, поэтому связать
   `queue_numbers` с `evotor_docs` автоматически нельзя без дополнительного поля в extras
   (например `sc.ord`). Требует согласования контракта чека.
2. **Реальный терминал.** Что PrintExtra печатается в выбранном месте и что `printGroupId`
   из текущего чека корректен — проверяется только на устройстве; при другом поведении
   зафиксировать факт и скорректировать место печати.
3. **Смена.** Правило сброса — календарный день. Если бизнес захочет нумерацию по смене,
   меняется `periodKeyOf` (одна функция).