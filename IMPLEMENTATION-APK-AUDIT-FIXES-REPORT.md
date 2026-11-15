# IMPLEMENTATION-APK-AUDIT-FIXES-REPORT

HEAD before: `e0fea77` (ветка `main`, ahead 5)  
HEAD after: local commit с D-01…D-12 (см. `git log -1`)  
versionCode / versionName: **170 / 5.3.11-audit-fixes**

## Per defect

| ID | Done | Files | Tests |
|---|---|---|---|
| D-01 | yes | `CardSession.kt`, `LoyaltyApi.kt`, `CardProgress.kt`, `ClientOffers.kt`, `LoyaltyBenefit.kt`, `DiscountCalc.kt`, `FiscalDiscount.kt`, `SellLauncher.kt`, `DiscountIntegrationService.kt` | `CardProgressTest`, `ClientOffersTest`, `FiscalDiscountTest`, `GiftRulesTest`, `CardSessionExtrasTest` — PASS |
| D-02 | yes | `ProductConfigGuard.kt`, `MainActivity.kt` (item + combo sheet), `ProductConfigGuardTest.kt` | `ProductConfigGuardTest`, `ConfigSheetRenderTest`, `ConfigOptsMapperTest` — PASS |
| D-03 | yes | `ReservationPolicy.kt` (new), `CardProgress.kt`, `DiscountCalc.kt` | `CardProgressTest`, `ClientOffersTest`, `DiscountCalcTest`, `FiscalDiscountTest` — PASS |
| D-04 | yes | `CardSession.kt`, `LoyaltyApi.kt`, `CardProgress.kt`, `CardRefresh.kt`, `LoyaltyBenefit.kt`, `MainActivity.kt` | `CardProgressTest` (kind token/code), `CardRefreshTest`, `CardSessionExtrasTest` — PASS |
| D-05 | yes | `ClientOffers.kt`, `ClientOffersTest.kt` | `ClientOffersTest` — PASS |
| D-06 | yes | `MainActivity.kt` (`openClientScreen` → `offers.any`) | `BenefitPromptGuardTest`, `ClientOffersTest` — PASS |
| D-07 | yes | `ConfigSheetDebug.kt`, `MainActivity.kt` | `ConfigSheetDebugTest` — PASS |
| D-08 | yes | `OptionPicker.kt` (`billableSelection`/`billableSum`), `OptionPickerBillableTest.kt` | `OptionPickerBillableTest`, `ConfigOptsMapperTest`, `DrinkConfigTest` — PASS |
| D-09 | yes | `CatalogCache.kt` (`pickSource`), `CatalogCacheTest.kt` | `CatalogCacheTest`, `CatalogApiMergeTest` — PASS |
| D-10 | yes | `ScannerReceiver.kt` (`codeFromStrings` + whitelist), `ScannerReceiverTest.kt` | `ScannerReceiverTest`, `OrderScanRouterTest` — PASS |
| D-11 | yes | `CardTtlPolicy.kt` (new), `CardSession.kt`, `CardTtlPolicyTest.kt` | `CardTtlPolicyTest`, `CardSessionExtrasTest` — PASS |
| D-12 | yes | `README.md` (170 / 5.3.11-audit-fixes) | n/a |
| D-13 | KEPT | `sell/ReceiptPlan.kt` не менялся: synthetic-guard сохранён | `ReceiptPlanTest` (existing) |
| D-14 | yes (doc) | `CatalogMode.kt` — комментарий: server flag сам по себе ничего не включает | n/a |
| D-15 | **SKIP** | orders auth / `X-Device-Token` / enroll — backend-контур, APK headers не расширялись | n/a |

## Behavior matrix loyalty

| case | gift UI | sc free/cb | canRedeem |
|---|---|---|---|
| code `freeAvailable=1` | off | absent (`free=0`, `cb=0`) | false |
| token `canRedeem=false` | off | absent (`free=0`, `cb=0`) | false |
| token `reserved` `canRedeem=true` | on | present | true |

Дополнительно:
- `CardSession.sc` пишет `op` при наличии `reservationId`, но `free/cb/voucherId` — только при `canRedeem`.
- `SellLauncher` и `DiscountIntegrationService` считают скидку только при `reserved && canRedeem`.
- `CardProgress.redeemEnabled` и `ClientOffers.gift/cashback/vouchers` гейтятся `canRedeem`.

## Config gate

| case | В чек |
|---|---|
| `needsConfig`, `cfg == null` | **blocked**: `Нет схемы с сервера — продажа заблокирована, повторите` |
| `needsConfig`, `cfg` ok | по существующим правилам `ProductConfigGuard.blockers` |
| `!needsConfig`, `cfg == null` | allowed legacy (простой товар без схем) |

`needsConfig = supportsMods || groupId != null || productIdOf(uuid) != null`.  
Гейт применён и к обычному item-sheet, и к combo-sheet.

## Grace

`ReservationPolicy.GRACE_SEC = 120` — единое значение для `CardProgress` и `DiscountCalc`.

## Tests

Command: `./gradlew testDebugUnitTest --console=plain`  
Result: **617 tests, 0 failures, 0 errors, 0 skipped** (75 XML).  
Baseline: 596 tests, 0 failures. Прирост: **+21 тест**.

## Build

- Command: `./gradlew assembleRelease --console=plain`
- Result: `BUILD SUCCESSFUL`
- APK: `app/build/outputs/apk/release/app-release.apk` (≈5.9 MB)
- Badging: `package: name='ru.sixthcup.evotor' versionCode='170' versionName='5.3.11-audit-fixes'`
- `minSdk=23`, `targetSdk=30`, `compileSdk=34`
- Release подписан debug-ключом (внешний keystore не задан); `applicationId`, signing config и integration-library не менялись.

## Self-check

- [x] `canRedeem` parsed and gates all discount paths
- [x] `kind` from server with fallback to code shape
- [x] single grace (`ReservationPolicy.GRACE_SEC = 120`)
- [x] `config_unavailable` blocks `needsConfig`
- [x] vouchers gated by `canRedeem + reserve + token`
- [x] ClientScreen not auto-opened without offers
- [x] `sendToReceipt=false` excluded from sum/charged selection
- [x] debug panel not in release (`BuildConfig.DEBUG`)
- [x] cache store-safe (`pickSource` ignores v1 when store known)
- [x] scanner whitelist only
- [x] card TTL + non-empty cart/pending keeps stale card
- [x] README updated
- [x] no `applicationId`/signing change
- [x] `testDebugUnitTest` green
- [x] release APK built
- [x] D-13 synthetic guard kept
- [x] D-15 explicitly skipped

## WHAT I DID NOT DO

- D-15: не добавлял `X-Device-Token`/enroll и не менял orders auth — это backend-контур (вариант A).
- Не трогал backend/PWA/админку.
- Не включал `SERVER_CATALOG_ALLOWED` (D-14 только комментарий).
- Не деплоил prod и не делал push.
- Не проверял на реальном терминале 5″/7″/10″, сканере, принтере, live proxy/backend.
- Не менял `ReceiptPlan` synthetic-guard (D-13).
