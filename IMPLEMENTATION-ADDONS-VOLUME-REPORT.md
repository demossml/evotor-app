# IMPLEMENTATION — addons volume (APK 5.2.12 / 137)

## Phase 0
- API_OK: catalog C1a already has milk/syrup/extra x18 for Cappuccino sizes (prior prod + artifacts).
- Branch targeted: API_OK_MERGE_FAIL — local volume via #group=, toppings empty after weak match.

## Code changes
1. `Names.looseNameGroupId` — map stripped local name with extra tokens to unique server nameGroupId key.
2. `CatalogApi.mergeWithStats` — use looseNameGroupId so byGroupId toppings attach even without uuid/exact name.
3. `SheetToppings` — third fallback: lastEnrich payload byGroupId / loose name (MainActivity passes lastEnrich).
4. versionCode **137**, versionName **5.2.12-addons-volume**.

## Backend
Unchanged. Catalog contract already correct.

## Tests
`VolumeToppingsFallbackTest` (3 cases) + existing SheetToppings/merge tests should still apply.
Gradle not run in this environment — run on MacBook: `./gradlew :app:testDebugUnitTest :app:assembleRelease`.

## Verify on device
1. Install 137, load data.
2. logcat SC_TOP: topsPerVariant should be >0 when catalog has toppings.
3. Cappuccino sheet: Volume → Milk → Syrup → Extra selectable.

## Not done
UI polish, wipe-catalog, backend groups[].variants toppings duplicate.
