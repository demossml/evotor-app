# Block 02 — searchrow + scan-btn (index → Android)

Источник: `index.html` .searchrow / .search / .scan-btn; `app.js` search input + searchClear + scanBtn→openGuest.

## Что сделано
- Ряд: search (flex) + scan-btn 48×48, gap 8dp.
- Search: height 48, radius 14, surface + line, icon ⌕, placeholder **«Найти напиток»**, clear ✕ (виден только при непустом query).
- Фильтрация: тот же `query` + `refreshRows(180ms)` — без смены логики каталога.
- scan-btn: открывает Screen.CARD (openGuest), как в прототипе. Аппаратный сканер Эвотора по-прежнему через ScannerReceiver → applyScan → LoyaltyApi.resolve.

## Не трогали
- focus / cats / menu-grid / cartbar / sheets
- Evotor Inventory / Sell / loyalty resolve контракт
