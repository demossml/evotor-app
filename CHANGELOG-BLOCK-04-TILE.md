# Block 04 — menu-grid / .tile (index → Android)

Источник: index `.menu-grid`, `.tile`, `.tile-media`, `.tile-flag`, `.tile-qty`, `.tile-ctl`, `.tile-body`, `.tile-name`, `.tile-meta`, `.tile-price`.

## Что сделано
- Карточка товара: radius 18, border line, surface, elevation.
- media: height 96dp, тёплый градиент #f4efe6 → #ece4d8.
- flag: ХИТ (featured) / КОМБО (isCombo) — только из существующих полей данных.
- qty badge: ink, 9px, top-right (как .tile-qty).
- controls: +/− 30dp круги; + = ink; − = surface+line, виден только при qty>0.
- body: name 12.5 bold, meta 9 muted (groupName), price 14 ink.
- gap ≈ 9–10dp через margins.
- Колонки: без изменений (2 / 3 по ширине экрана).
- onTap / onInc / onDec / onInfo — прежние колбэки.

## Не трогали
- cartbar, sheets (item/cart/rec/pay)
- Evotor / API
