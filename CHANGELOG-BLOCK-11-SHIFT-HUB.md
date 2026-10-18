# Block 11 — Shift hub (index shift-page shell)

## UI
- shift-chip → Screen.SHIFT
- Head «← К заказам» / «Смена»
- Hero без выдуманных метрик (только пояснение Эвотор + будущий API)
- Сетка: Операции · Статистика · Команда · Табель · Перерывы · Закрыть смену
- Подэкраны — заглушки с честным текстом «нужен API / Эвотор»
- «Сервис · каталог / карта» = прежний showMore

## Backend (нужно для блоков 12+)
Предлагаемые контракты (ещё не в APK — согласовать с бэкендом):

### GET /api/devices/shift/summary
Ответ: status, employeeName?, role?, openedAt?, receiptsCount?, revenueKopecks?, cashKopecks?, cardKopecks?

### GET /api/devices/timesheet?month=YYYY-MM
Ответ: days[] { date, status: work|off|wish|sick|…, label? }, periodEditable?

### GET /api/devices/timesheet/wishes?period=…
### PUT /api/devices/timesheet/wishes
Пожелания выходных (лимиты с бэка).

### GET /api/devices/shift/team
Кто на смене (read-only).

Фискальные ops (внесение/изъятие/X) — через Integration Library Эвотора, не наш backend.
