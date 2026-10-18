# Block 13 — Пожелания по выходным

## UI
- Целевой месяц (следующий календарный)
- Pill: Открыто / Черновик / Локально
- Правила: «с сервера» (не выдуман лимит)
- Календарь выбора дат
- Отправить → Toast «API не подключён», статус sent_local
- Note: приложение не назначает выходные

## Backend
```
GET  /api/devices/timesheet/wishes
PUT  /api/devices/timesheet/wishes
  body: { year, month, days: [1,5,12] }
→ { status, limit, periodLabel, canEdit, accepted[], declined[{day,reason}] }
```
