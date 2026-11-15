# STEP-APK-AUDIT-PROGRESS

| STEP | Files | Tests |
|---|---|---|
| STEP-0 baseline | `app/build.gradle.kts` (read-only) | 596 tests, 0 failures (71 XML) |
| STEP-1 D-01 canRedeem | `CardSession.kt`, `LoyaltyApi.kt`, `CardProgress.kt`, `ClientOffers.kt`, `LoyaltyBenefit.kt`, `DiscountCalc.kt`, `FiscalDiscount.kt`, `SellLauncher.kt`, `DiscountIntegrationService.kt` + 8 test files | targeted 8 classes: PASS |
| STEP-2 D-04 kind | `CardSession.kt`, `LoyaltyApi.kt`, `CardProgress.kt`, `CardRefresh.kt`, `LoyaltyBenefit.kt`, `MainActivity.kt` + `CardProgressTest` | targeted 5 classes: PASS |
| STEP-3 D-03 grace | `ReservationPolicy.kt` (new), `CardProgress.kt`, `DiscountCalc.kt`, `ClientOffersTest.kt`, `CardProgressTest.kt` | targeted 4 classes: PASS |
| STEP-4 D-02 config gate | `ProductConfigGuard.kt`, `MainActivity.kt` (item+combo), `ProductConfigGuardTest.kt` (new) | targeted 4 classes: PASS |
| STEP-5 D-05 vouchers | `ClientOffers.kt`, `ClientOffersTest.kt` | targeted 3 classes: PASS |
| STEP-6 D-06 ClientScreen | `MainActivity.kt` (`openClientScreen`) | targeted 2 classes: PASS |
| STEP-7 D-08 sendToReceipt | `OptionPicker.kt`, `OptionPickerBillableTest.kt` (new) | targeted 3 classes: PASS |
| STEP-8 D-07 debug panel | `ConfigSheetDebug.kt`, `MainActivity.kt` | targeted `ConfigSheetDebugTest`: PASS |
| STEP-9 D-09 cache | `CatalogCache.kt`, `CatalogCacheTest.kt` | targeted 2 classes: PASS |
| STEP-10 D-10 scanner | `ScannerReceiver.kt`, `ScannerReceiverTest.kt` (new) | targeted 2 classes: PASS |
| STEP-11 D-11 TTL | `CardTtlPolicy.kt` (new), `CardSession.kt`, `CardTtlPolicyTest.kt` (new) | targeted 2 classes: PASS |
| STEP-12 D-12 README | `README.md` (170 / 5.3.11-audit-fixes) | n/a |
| STEP-13 D-14 doc | `CatalogMode.kt` comment only | n/a |
| STEP-14 D-13/D-15 | no code changes (D-13 kept, D-15 SKIP) | n/a |
| STEP-15 release | `app/build.gradle.kts` 170 / 5.3.11-audit-fixes, README, report | 617 tests, 0 failures; `assembleRelease` OK; badging 170 / 5.3.11-audit-fixes |
