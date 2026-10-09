# CHANGELOG — Barista V2 loyalty hotfix

- `versionCode`: 63 → 64.
- `versionName`: `4.1.0-barista-v2` → `4.1.1-barista-v2`.
- `LoyaltyApi`: `CardInfo.cashbackKopecks` now reads the available balance from `cashback`, not `cashbackReserved`.
- `InventoryRepository`: every sellable non-GROUP inventory item is marked `freeEligible = true`.
- The existing UI eligibility check and `DiscountIntegrationService` use the same `CartLine.freeEligible` rule; the gift prompt requires `freeAvailable > 0` and a non-empty eligible cart. The discount remains limited to the cheapest eligible unit.
- No backend, PWA, admin, API URL, proxy secret, `sc` extras contract, scanner, inventory source, or cleartext setting changes were made.

## Verification

- Source-level checks: verify `cashback` mapping and the shared eligibility rule.
- APK build was not run as part of this hotfix; install only after a successful local `./gradlew :app:assembleDebug` and device testing.
