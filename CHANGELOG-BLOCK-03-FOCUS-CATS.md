# Block 03 — focus + cats (index → Android)

Источник: index `.focus` / `.focus-card` / `.cats` / `.chip` / `.row-head` / `.eyebrow` / `.menu-title`.

## Что сделано
- Секция focus: заголовок «СЕЙЧАС БЕРУТ» (eyebrow), карточки 158×74, img 53×58, name 11.5, price 11, badge КОМБО, кнопка +.
- Данные focus: по-прежнему `featured` / soft-focus из CatalogApi (без выдуманных товаров).
- Тап по карточке → onTap (как меню); «+» → onInc.
- cats: чипы height 38, pill, gap 6; selected = ink (#17140F), не brand-синий — как `.chip.on` в CSS.
- Заголовок меню: «Всё меню».

## Не трогали
- menu-grid tile styling (блок 04)
- cartbar, sheets
- Evotor / API
