# Fix report: product modifier schemes not opening on cashier

## Root cause found in cashier enrichment flow

`CatalogApi.parse()` previously discarded every backend product that had no `evotorUuid`. The cashier then keyed all enrichment exclusively by UUID and `MainActivity` abandoned the enrichment pass when that UUID map was empty. As a result, even when `/api/devices/catalog` returned the product's `toppings[]` scheme, the APK could never merge it into the local Evotor inventory item if its UUID link was absent or different for that store. Without merged toppings, `showItemSheet()` had no scheme to show.

## Changes

- Keep catalog enrichment entries even if their `evotorUuid` is absent.
- Continue preferring exact Evotor UUID matches.
- Add a normalized exact-name fallback only when that name is unique in the backend catalog; ambiguous names are intentionally excluded.
- Keep the name index in the local enrichment cache.
- Allow `MainActivity` to merge name-indexed enrichment instead of returning early when UUID mappings are absent.
- Read `required` from the backend schema and preselect/lock available required options.
- Add unit tests for name fallback and UUID-match precedence.
- Bump Android app version to `4.9.4-toppings-link-fallback` / versionCode 103.

## Safety

The fallback only associates menu metadata; it does not invent Evotor UUIDs. Paid toppings still require a real `evotorUuid` to be added as a fiscal line. If the scheme is visible but a paid topping says it is unavailable, inspect the backend's `product_store_links` for the modifier's matching product and confirm catalog sync for that store.

## Verification limitation

The Gradle unit-test command was attempted, but the wrapper could not download Gradle because this environment has no DNS/network access (`services.gradle.org`). Source changes and tests are included; a successful Android build has not been claimed.
