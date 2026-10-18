# Block 12 — Табель (calendar UI)

## UI (index)
- Store line «6.7 Coffee · без переключения»
- Banner: график после API (без демо-смен)
- Month bar ‹ › + название месяца
- Сетка Пн–Вс, дни месяца, today/selected
- Деталь выбранного дня
- CTA «Пожелания по выходным →» (shiftView=wishes → блок 13)
- empty / loading / error состояния

## Не выдумано
- Нет списка сотрудников и часов смен
- Read-only на кассе

## Backend (подключить позже)
```
GET /api/devices/timesheet?month=YYYY-MM
→ {
  storeName, status: "approved"|"preliminary"|"empty",
  days: [{ day: 1..31, status, start?, end?, label? }]
}
```
