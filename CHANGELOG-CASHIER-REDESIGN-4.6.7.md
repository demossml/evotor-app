# 6.7 Coffee cashier UI — 4.6.7

## UI changes
- Kept the existing Kotlin + classic Android Views stack and Evotor integration.
- Refined the product tiles: consistent card height, 20dp radius, more readable price, less photo height to improve menu density on a 5-inch portrait terminal.
- Updated search to a clearer touch-first field and increased common control radii while preserving the 48dp minimum touch targets.
- Modifier sheet now shows the product photo, product price, live total including selected sellable modifiers, and clearer primary action labels.
- On displays >= 600dp wide, the order screen uses a persistent right-side cart summary; on displays >= 800dp, the catalog uses three columns. On narrower screens, the compact bottom cart/pay bar remains.
- Sidebar cart refreshes when the order changes; the existing cart sheet remains the editing surface.
- Keeps card progress / cashback in the persistent header and preserves native Evotor receipt/payment screens.

## Cart correctness for configured drinks
- Added a local `cartKey` separate from the Evotor `productUuid`.
- Different modifier configurations for the same drink now remain separate cart rows; identical configurations can merge.
- The cart key is local UI state only. Fiscal receipt positions still use the actual Evotor product UUID.
- Cart quantity badges aggregate configured variants of the same product.
- Older saved cart records remain readable; `cartKey` falls back to the product UUID.

## Small reliability fix
- The successful catalog refresh timestamp is now separate from the last-attempt timestamp, so failed local inventory reads are not treated as a successful refresh and do not trigger a reload on every screen resume.

## Version
- `versionCode = 82`
- `versionName = 4.6.7-cashier-redesign`

## Validation status
- Source changes are prepared; APK build and Android unit tests have **not** been confirmed in this environment because `services.gradle.org` could not be resolved.
- Before production installation, build on the MacBook and test on a physical Evotor terminal. In particular, verify the fiscal receipt contains only real Evotor UUIDs and that modifier totals match the receipt.
