# Barista flow V2 — 6.7 Coffee on Evotor

## Screens

Cold start has one card/QR field, OK for manual short numeric codes, and a secondary “Продолжить без карты”. Signed QR values are resolved automatically after the scanner broadcast or after the QR-shaped token is pasted. A successful resolve opens the order screen. Errors stay on the card screen and sales without loyalty remain available.

The order screen uses the Evotor terminal inventory (`ProductTable` / `InventoryApi`) as the only catalog source. There is no local seed catalog. Prices are displayed from the terminal inventory in rubles and product UUIDs are passed to `OpenSellReceiptCommand`. If the terminal inventory is empty, no products can be sold from this UI.

## Resolve contract

`POST /api/devices/loyalty/resolve`, JSON `{ "code": "<signed card token or numeric card code>" }`. The app uses the existing configured HTTPS Evotor proxy path; it does not embed the proxy token or use the legacy device-enroll flow. Backend response is authoritative. Signed QR tokens are passed intact; `sc1.*` account-recovery codes are not card tokens and should not be used at the till. Numeric card codes resolve identity for accrual only according to backend policy.

## Loyalty and extras

The receipt extras contract remains `extras.sc`, with `v:2`, `c` (signed token or numeric code), optional `op` (reservation ID), `free` (applied free cup claim), `cb` (cashback claim in kopecks), and `ts`. This matches `SellHandler.getSc`. The checkout integration applies only cashier-selected benefits, bounded by the backend reservation and receipt total.

The APK's terminal inventory API does not expose backend `freeEligible` / `countsAsCup` metadata in the current implementation. To avoid incorrectly gifting an arbitrary product, the free-cup prompt is shown only when eligibility metadata is explicitly available; no product-name guess is used. Wiring the per-store product metadata to Evotor UUID links is a follow-up requirement for fully automatic eligibility.

## Session reset

Card state is one-sale state. A successful resolve creates the session; a card reset detaches loyalty but does not clear the cart. On the current Integration Library API, `openSellReceipt` confirms that the receipt was opened, not that fiscal payment has completed. When the cashier returns from the payment screen, the app asks for explicit confirmation: “Да, чек успешен” clears the cart and customer, while “Нет, оставить заказ” preserves them. This avoids treating “receipt opened” as a confirmed fiscal sale. Fully automatic reset still requires a documented fiscal-completion callback verified on a terminal.

## Manual terminal checklist

1. Install debug APK over the previous build (versionCode 63).
2. Cold start: card field focused; no enroll or server URL screen.
3. Resolve an existing signed QR; confirm card code, cups and cashback.
4. Resolve a nonexistent code; confirm error and that “Продолжить без карты” still works.
5. Scan the customer QR using the Evotor scanner; it should use the same field/resolve path.
6. Load catalog from Evotor inventory; confirm prices and UUIDs against the terminal catalog.
7. Add/remove quantities, reset customer and confirm cart remains.
8. Test cashier-selected cashback/free decisions and inspect `extras.sc` on the resulting SELL document.
9. Return from payment: confirm successful receipt clears customer/cart; confirm failed/cancelled payment keeps the order. This needs real-terminal verification.
