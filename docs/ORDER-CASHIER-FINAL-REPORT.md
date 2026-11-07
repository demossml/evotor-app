# ORDER-CASHIER-FINAL-REPORT

## Status
Additive Kotlin modules for pre-order receive on Evotor APK.

## Files added
- `net/OrderApi.kt` — resolve / active / status / reject / markPaid (HttpURLConnection + DeviceHeaders)
- `data/OrderScanRouter.kt` — detects `ord1.` and extracts oid
- `ui/orders/OrderDetailActivity.kt` — detail, status buttons, reject, pull hook
- `ui/orders/OrdersListActivity.kt` — list + 6-digit search + 10s poll

## MainActivity integration (required one-time)

In `handleScannedCode(code: String)` **before** loyalty resolve:

```kotlin
import ru.sixthcup.evotor.data.OrderScanRouter
import ru.sixthcup.evotor.ui.orders.OrderDetailActivity
import ru.sixthcup.evotor.ui.orders.OrdersListActivity

// inside handleScannedCode:
if (OrderScanRouter.isOrderQr(code)) {
    val oid = OrderScanRouter.extractOid(code)
    val i = Intent(this, OrderDetailActivity::class.java)
    if (oid != null) i.putExtra(OrderDetailActivity.EXTRA_OID, oid)
    else i.putExtra(OrderDetailActivity.EXTRA_CODE, code) // fallback
    startActivity(i)
    return
}
```

Register activities in `AndroidManifest.xml`:

```xml
<activity android:name=".ui.orders.OrderDetailActivity" android:exported="false" />
<activity android:name=".ui.orders.OrdersListActivity" android:exported="false" />
```

Add entry point button/menu item → `OrdersListActivity`.

## Pull into receipt
`OrderDetailActivity` writes `pull_order_id` to SharedPreferences `preorder`.
Host should map `item.evotorProductUuid` through existing `InventoryRepository` / `createPosition` path (same as catalog add).

After successful SELL call:
`OrderApi.markPaid(orderId)` on background thread.

## Tests / verification
1. Build: `./gradlew :app:assembleDebug`
2. Scan synthetic payload `ord1.` + base64 payload with oid → opens detail or shows server error
3. Loyalty QR still goes existing path
4. Orders list shows active; 6-digit opens detail
5. Status buttons only forward: accepted → cooking → ready

## Known limitations
- UI is code-built (no XML) to avoid resource merge issues in this package
- Notifications channel not fully wired (toast on new id in list session)
- PATCH may need server to accept method override on some proxies (client sets real PATCH)
