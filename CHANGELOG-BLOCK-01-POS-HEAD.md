# Block 01 — pos-head (index → Android)

Источник: `index.html` header.pos-head + `app.css` (.pos-head, .lockup, .brand-line, .shift-chip, .guest-chip).

## Что сделано
- Шапка экрана ORDER: lockup слева (logo 34dp + «6.7 Coffee» + shift-chip), guest-chip справа.
- Logo: `res/drawable/ic_logo_67.xml` (упрощённый tile из logo-67-tile.svg, цвет #002FA7 / #F6F3EE).
- shift-chip: зелёная точка + «Смена открыта» (без выдуманного ФИО). Тап открывает **существующее** меню showMore (обновить каталог / карта / сброс / очистить корзину).
- guest-chip: avatar 33dp, имя, «N из M чашек» / «Карта не привязана». Тап → Screen.CARD (как раньше).
- Цвета из UiKit (parity base.css).
- Логика Эвотор / CatalogApi / LoyaltyApi / SellLauncher **не менялась**.

## Что не трогали
- searchrow, focus, cats, menu-grid, cartbar, sheets — следующие блоки.
