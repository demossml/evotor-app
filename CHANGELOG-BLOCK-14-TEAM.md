# Block 14 — Команда (read-only)

## UI
- Meta: текущая смена · 0 сотрудников
- Empty-state без демо-ФИО
- 3 skeleton-ряда (визуал)
- Note: без рейтингов

## Backend
GET /api/devices/shift/team
→ { members: [{ name, role, status, receipts?, revenueKopecks? }] }
