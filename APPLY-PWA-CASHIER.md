# 6.7 Evotor — PWA cashier patch (4.6.3 / versionCode 78)

База: `evotor-app-main (3)-2.zip` (4.6.2 / 77).
Сборка на устройстве не проверялась (нет Android SDK в среде).

## Что сделано (шаги 01–05)

### 01 Critical
| Файл | Изменение |
|------|-----------|
| `SixthCupApp.kt` | `Cart.attach(this)` в `Application.onCreate` — скидка free cup не теряется после kill процесса на оплате |
| `SellLauncher.kt` | Пропуск позиций с `productUuid` вида `mod:…`; пустой чек → ошибка |

### 02 Tokens (= PWA app.67coffee.ru)
| Файл | Изменение |
|------|-----------|
| `res/values/colors.xml` | page **#F6F3EE**, brand **#002FA7**, ink/hint/line под тёплый фон |
| `res/values/dimens.xml` | radius 12/16/20/24, touch 48 |
| `res/values/themes.xml` | windowBackground page, colorPrimary brand |
| `UiKit.kt` | PAGE=#F6F3EE, INK/HINT обновлены |
| `MainActivity` companion | токены из UiKit |

### 03 Photo offline
| Файл | Изменение |
|------|-----------|
| `data/CatalogCache.kt` | **новый** — persist imageUrl (+ meta) на диск |
| `MainActivity.loadProducts` | load cache before network; save after successful fetch |
| `ThumbLoader.kt` | MASTER_PX 288 → **480** |
| `ThumbView.kt` | placeholder **«6.7»** (бренд), не инициалы |

### 04 Grid tiles
| Файл | Изменение |
|------|-----------|
| `CatalogAdapter.ProductVH` | вертикальная плитка: фото сверху 120dp, имя, цена |
| `MainActivity` | `GridLayoutManager(2)`, span 1 для Item, span 2 для Focus/Header/State |
| Старт | экран **ORDER (витрина)**, не CARD |

### 05 SELL_MAIN
| Файл | Изменение |
|------|-----------|
| `AndroidManifest.xml` | filter `ru.evotor.intent.action.SELL_MAIN` + EVOTOR + LAUNCHER (плитка MAIN+EVOTOR сохранена) |

## Как накатить

1. Заменить файлы из этого архива поверх рабочей копии (или собрать этот tree).
2. `versionCode` 78 / `versionName` 4.6.3-pwa-cashier — установка поверх 77.
3. `app_uuid` / `applicationId` **не** менялись.

## Проверка на терминале

1. Холодный старт → витрина 2 колонки, фон тёплый.
2. Фото: онлайн → offline kill → фото из кэша (после хотя бы одной успешной загрузки каталога).
3. Товар без imageUrl → «6.7» на soft blue.
4. Карта + free cup → убить процесс на оплате → скидка не должна обнулиться.
5. Добавка только `mod:…` не уходит в чек.
6. Кнопка «Продажа» (если ОС подхватила SELL_MAIN) → 6.7. Если конфликт — уберите второй intent-filter.

## Не сделано (следующая итерация)

- Полные modifier groups (single/multi) + обязательный evotorUuid на добавках в UI
- Loyalty snapshot в CheckoutChoice для сервиса
- 3 колонки на 7"/10"
- Серверный WebP 480/960
- Compose / смена стека

