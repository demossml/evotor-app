# MAC EVOTOR APK — финальный отчёт (предзаказы гостя)

## STAGE 0 — Инспекция
- Репозиторий: `evotor-app`, модуль `app`, package `ru.sixthcup.evotor`
- Ветка: **`feature/preorder-cashier`** (создана от `main` @ `50a81a0`)
- Версии: было `159` / `5.3.0-choice-scheme` → стало **`160` / `5.3.1-preorder`**
- Бэкап: `.preorder-backup/AndroidManifest.xml.bak`, `.preorder-backup/MainActivity.kt.bak` (папка в `.gitignore`)

## STAGE 1 — Kotlin-модули
| Файл | Строк |
|---|---|
| `net/OrderApi.kt` | 218 |
| `data/OrderScanRouter.kt` | 38 |
| `ui/orders/OrderDetailActivity.kt` | 185 |
| `ui/orders/OrdersListActivity.kt` | 107 |
| `docs/ORDER-CASHIER-FINAL-REPORT.md` | — |

`DeviceHeaders` не дублировался (используется существующий), `BuildConfig.API_BASE_URL` есть.
Компиляция после копирования: OK.

## STAGE 2 — Manifest, скан, вход
- Manifest: `.ui.orders.OrderDetailActivity`, `.ui.orders.OrdersListActivity` (`exported=false`, portrait).
- `MainActivity.handleScannedCode` — в начале ветка:
```kotlin
if (OrderScanRouter.isOrderQr(trimmed)) {
    val i = Intent(this, ru.sixthcup.evotor.ui.orders.OrderDetailActivity::class.java)
    OrderScanRouter.extractOid(trimmed)?.takeIf { it.isNotBlank() }?.let {
        i.putExtra(ru.sixthcup.evotor.ui.orders.OrderDetailActivity.EXTRA_OID, it)
    }
    Log.i(TAG, "order_qr_scan oid=${OrderScanRouter.extractOid(trimmed) != null}")
    startActivity(i)
    return
}
```
Loyalty-ветка ниже не тронута.
- Вход: кнопка **«Заказы»** в шапке (`ordersChip()`), рядом с индикатором очереди.

## STAGE 3 — Версия и сборка
```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
BUILD SUCCESSFUL
```
- тесты: **522 / 0 fail / 0 errors**, 61 класс (было 518; +4 `OrderScanRouterTest`)
- lint: **0 errors, 107 warnings**
- `aapt2 badging`: `versionCode='160' versionName='5.3.1-preorder'`
- подпись: `CN=6.7 Coffee, OU=POS, O=6.7 Coffee` (release)
- APK: `dist/sixthcup-evotor-5.3.1-preorder-vc160.apk` (6 136 555 байт)
  sha256 `e4fe3b0d82585b4bae12670e2f4b635a691b4476322aca259fca14738ccb9474`
- копия: `~/Downloads/evotor-app-5.3.1-preorder-v160.apk`

## STAGE 4 — Чеклист
`docs/APK-PREORDER-HANDOFF.md` создан. Коммит в ветке `feature/preorder-cashier`.

## Регресс-контроль
| Что проверялось | Результат |
|---|---|
| Все прежние фиксы на месте (choice-scheme 159, milk-validator 158, VERTICAL picker, debug-панель, VolumeResolver) | да, архив не перезаписывал дерево — только добавлены 4 файла |
| Loyalty-скан не перехватывается | `OrderScanRouterTest`: карта/цифры/`order1.` → не order QR |
| Фискальный путь (`SellLauncher`, `Fiscal*`, `ReceiptPlan`) | не изменялся |
| 522 юнит-теста | 0 падений |

## Ограничения (честно)
1. Экраны заказов **не проверены на терминале** — сборка и компиляция только.
2. `markPaid` нигде не вызывается: после оплаты заказ не помечается оплаченным (TODO).
3. «Подтянуть в чек» — заглушка: тост + `pull_order_id` в SharedPreferences, в корзину ничего не кладёт.
4. Backend `/api/devices/orders/*` с этой машины не проверялся.
5. Порядок статусов предполагает, что гость оформляет заказ заранее; фискальный чек по-прежнему только через обычную продажу.

```
APK_READY: /Users/dmitrijsuvalov/Documents/evotor-app/dist/sixthcup-evotor-5.3.1-preorder-vc160.apk
VERSION_CODE: 160
VERSION_NAME: 5.3.1-preorder
```
