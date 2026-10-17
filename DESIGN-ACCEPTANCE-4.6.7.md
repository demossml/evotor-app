# UI acceptance checklist — 6.7 Coffee Evotor 4.6.7

## 5-inch portrait (primary)
- [ ] The catalog is the first screen; no forced card-entry screen.
- [ ] Guest card / cups / cashback state is visible in the header and opens by tap.
- [ ] Search and category chips are reachable without opening a menu.
- [ ] Two-column photo-first product grid; product names and prices remain readable.
- [ ] Main actions have at least 48dp touch targets.
- [ ] Cart summary and “К оплате” remain fixed at the bottom.
- [ ] Modifier sheet shows photo, base price, available options, live total and “В чек · добавить”.

## 7-inch / 10-inch portrait
- [ ] On width >= 600dp a persistent cart summary appears on the right.
- [ ] On width >= 800dp the catalog uses three columns.
- [ ] “Изменить заказ” opens the existing cart editor; checkout still uses the same loyalty logic and native Evotor payment.

## Business-critical scenarios
- [ ] Same drink with different modifier configurations remains as separate drink rows in the cart.
- [ ] The fiscal receipt uses actual Evotor product UUIDs only; the UI-only cart key never reaches the Evotor API.
- [ ] Cart totals, modifier prices, loyalty discounts and checkout totals reconcile.
- [ ] Card QR / manual entry, online/offline messaging, gift reservation and cashback behavior are unchanged.
- [ ] Double-tap payment guard, cancel/retry and return from native payment screen work.
- [ ] Empty catalog, loading skeleton, no search results, catalog retry and no-network states are understandable.
