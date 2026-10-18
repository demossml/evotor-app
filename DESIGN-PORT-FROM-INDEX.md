# Port design from index-design → Android (Evotor APK)

Источник: `index.html` + `assets/base.css` + `assets/app.css` + `assets/app.js`.

## Токены (уже в UiKit / colors.xml)

| Token | Hex | Использование |
|-------|-----|---------------|
| brand | #002FA7 | Только primary CTA («К оплате», «В чек») |
| bg / page | #F6F3EE | Фон экрана |
| surface | #FFFFFF | Карточки товаров, sheets |
| ink | #17140F | Основной текст |
| ink-2 | #464036 | Вторичный |
| muted | #776F61 | Подписи, placeholders |
| line | #E7DFD3 | Разделители, бордеры |
| success | #1C7A4B | Успех / подарок |
| danger | #A52A2A | Ошибки |
| radius | 10 / 14 / 20 / pill | r-s / r-m / r-l |
| tap | ≥48dp | Все главные кнопки |

Шрифт в прототипе — Onest. На Android: системный sans + `fontFamily` при наличии ttf в assets (опционально).

## Экраны прототипа → текущий код

| Прототип | Android | Статус |
|----------|---------|--------|
| pos-head (logo + shift + guest) | topBar + guest chip | Частично: guest есть, shift — заглушка |
| search + scan | searchView + ScannerReceiver | Есть |
| focus «Сейчас берут» | chips / featured из CatalogApi | Есть featured flag |
| product grid 2/3 col | CatalogAdapter + GridLayoutManager | Есть, подстроить spans 5″/7″/10″ |
| modifier sheet | BottomSheet в MainActivity | Есть; выровнять layout под прототип |
| cart sticky / bottom bar | bottomBar + payButton | Есть |
| checkout loyalty choices | dialogs / screens | Есть (подарок / кэшбэк) |
| timesheet / wishes | — | **Не в scope APK** |

## Чеклист 100% visual parity (следующая итерация)

- [ ] Header: lockup logo 28dp + «6.7 Coffee» + shift chip (dot + label)
- [ ] Guest chip: avatar letter, name, «N из M чашек»
- [ ] Search row: rounded surface, clear, scan icon 48dp
- [ ] Focus strip horizontal scroll с photo tiles
- [ ] Product card: photo 1:1, name 2 lines, price, badge «Хит/Новинка»
- [ ] Modifier sheet: photo, sizes pills, milk, syrups, live total, CTA brand
- [ ] Bottom: cart summary + brand «К оплате · sum»
- [ ] 5″: 2 col; ≥600dp: cart справа; ≥800dp: 3 col
- [ ] Empty / skeleton / offline banners в danger-soft / warn-soft

## Evotor constraints (не менять)

- В чек только реальный `productUuid` из Inventory.
- Фискализация только через Эвотор.
- Loyalty extras в `SetExtra` / `extras.sc`.
- Нет секретов в APK.
