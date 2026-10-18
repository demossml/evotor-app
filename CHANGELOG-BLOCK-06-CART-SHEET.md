# Block 06 — cart sheet (index → Android)

Источник: index `#cartOverlay` / `.cart-sheet` / `.cart-row` / `.stepper` / `.sheet-foot` / `#goPay`.

## Что сделано
- Head: «Чек» + count badge + «Очистить» + close ✕
- order-type: «С собой» / «Здесь» (только UI-подпись, в Эвотор не уходит)
- Список: thumb 46, name, recipe/mods, price, stepper −/qty/+, remove ×
- Upsell track: из `upsellDefault` с реальным uuid в каталоге
- Note: текст про фискальный чек Эвотора (как в index)
- Foot: «Итого» + CTA «К оплате →» → showUpsellThenCheckout
- Confirm clear: карта остаётся, корзина чистится

## Не трогали
- item sheet / rec sheet / pay sheet
- SellLauncher / Inventory / loyalty API
