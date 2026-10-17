# 4.6.5-photos-auth (versionCode 80)

## Android
- CatalogApi: log HTTP status + count of products with imageUrl (Logcat tag `CatalogApi`).
- ThumbLoader: keeps normalize `/uploads` → `/api/devices/images` (from 4.6.4).
- No secret token in APK (Evotor injects on loyalty; catalog is public read on backend).

## Pair with backend zip
Deploy backend changes first, then install this APK.
