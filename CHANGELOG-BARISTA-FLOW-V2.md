# Barista flow V2

- `versionCode`: 62 → 63; `versionName`: `4.0.0-reservations` → `4.1.0-barista-v2`.
- Replaced separate card/catalog/cart navigation with card input → order/catalog/cart flow.
- Added automatic signed-QR resolve, manual numeric resolve, visible resolve errors, and continue-without-card.
- Catalog remains sourced only from Evotor terminal inventory; no seed menu or fake prices.
- Added quantity controls and explicit checkout choices for free cup / cashback; receipt extras continue to use backend `sc` v2 contract.
- Scanner receiver now forwards scan payload into the single Activity input flow.
- Known limitation: inventory metadata currently does not expose `freeEligible`/`countsAsCup`, and the SDK callback confirms receipt opening rather than completed fiscal payment, so return flow asks the cashier to confirm the result. See `docs/BARISTA-FLOW-V2.md`.
