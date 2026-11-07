# Схемы обязательного выбора («с корицей / без корицы») — отчёт

Исходники: `sixth-cup-main_15.zip`, `evotor-app-main_14.zip` (APK 158 / `5.2.32-milk-validator`).
Результат: backend + админка, APK **159 / `5.3.0-choice-scheme`**.

## Что сделано (по решениям владельца)
- Выбор — обязательный, цена 0 ₽, сумма не меняется. Схема назначается напиткам в админке (на каждый размер); без схемы блока на кассе нет.
- Бариста видит выбор **строками PrintExtra** рядом с «ЗАКАЗ № …» и подписью в корзине. Фискальной позиции и товара Эвотор нет; в ОФД/электронном чеке выбора не будет.
- Два талона (гость/бариста) и переделка номера очереди **не делались**. Данные `choiceTitle/choiceLabel` сохранены в строке корзины и в `ChoicePrintEntry` для будущего талона.
- `schemaVersion` остался 1; добавлен только ключ `choices`.

## Контракт
`GET /api/devices/products/:id/configuration` → `choices: null | { required:true, byProductId:{ "<pid>": { schemeId|null, title, options:[{id,label,isDefault}], error?:"invalid_scheme" } } }`. Цен и uuid нет. `GET /catalog`: у товара `choiceSchemeId` (null, если схема выключена). Готовность: `choice_scheme_empty`; `configErrors`: `choice_scheme_invalid`.

## Файлы
**Backend:** `db/migrations/041_choice_schemes.sql` (+ rollback), `modules/catalog/choiceScheme.ts` (новый), `productConfig.ts`, `readiness.ts`, `modules/devices/routes.ts`, `modules/admin/routes.ts` (CRUD `/choice-schemes`, `/choice-schemes/apply` с dryRun, поле `choiceSchemeId` в `/products` и `/product-groups`, запись запрещена marketer/manager), тесты `choice-scheme.test.ts` (новый, 19), `product-configuration-fixture.test.ts`, три фикстуры, `docs/CHOICE-SCHEMES.md`.
**Админка:** `pages/ChoiceSchemes.tsx` (раздел «Обязательный выбор» в навигации), `lib/choiceScheme.ts` + тест, `api/types.ts`, `MenuProducts.tsx` (селект «Обязательный выбор (без цены)» для товара и группы размеров), `lib/readiness.ts` + тест, `App.tsx`, `layout/Shell.tsx`.
**APK:** `ProductConfig.kt`, `ProductConfigApi.kt`, `ProductConfigCache.kt`, `ProductConfigGuard.kt`, `ConfigOptsMapper.kt`, `OptionPicker.kt` (блок между молоком и сиропом), `SheetSections.kt`, `Cart.kt`/`CartLines.kt` (`choiceLabel/choiceTitle`, ключ строки включает выбор), `ReceiptValidator.kt` (`choice_missing`), `RepeatOrder.kt`, новый `ChoicePrintStore.kt`, `SalePrintStore.kt` (`ReceiptPrintText`), `ReceiptPrintExtraService.kt`, `SellLauncher.kt`, `MainActivity.kt`, `build.gradle.kts`, 3 новых теста + правка `SheetSectionsTest`, фикстура `product-configuration-choice-fixture.json`, раздел в `docs/DYNAMIC-DRINK-CONFIGURATION.md`.
Не менялись: `FiscalLines`, `ReceiptPlan`, `DiscountCalc`, лояльность, подарок, сканер, ваучеры, валидатор молока (кроме одной ветки `choice_missing`).

## Тесты
| Набор | Результат |
|---|---|
| backend vitest | 89 файлов / **604** passed, `tsc --noEmit` чисто |
| админка vitest | 11 файлов / **60** passed, `npm run build` (tsc + vite) успешен |
| APK, чистая логика (kotlinc-обвязка, не Gradle) | **145** passed: 29 новых (парсер/кэш/guard, печать, корзина, валидатор, повтор, секции) + 116 существующих |

## PHASE V
| № | Проверка | Итог |
|---|---|---|
| 1 | Миграция только добавляет, есть откат | ok |
| 2 | Backend-тесты и tsc | ok |
| 3 | `schemaVersion` = 1, `choices` — добавочный ключ; фикстуры отличаются только добавлением | ok |
| 4 | Товар без схемы / выключенная схема: `choices:null`, блока и блокировки нет | ok |
| 5 | Админка: tsc, тесты, сборка | ok |
| 6 | APK: парсер, кэш, guard (повреждённая схема блокирует, объём без схемы — нет) | ok (обвязка) |
| 7 | Выбор не `Topping`/не позиция чека, сумма не меняется | ok (тест + нет правок фискальных файлов) |
| 8 | «с корицей» и «без» не склеиваются в одну строку корзины | ok (логика ключа в `OptionPicker.signature`, не проверена на UI) |
| 9 | Печать: порядок номер → выбор → лояльность, без выбора текст прежний, ширина 32 | ok (тест) |
| 10 | «Повторить» не восстанавливает строки с выбором | ok (тест) |
| 11 | versionCode 159 / `5.3.0-choice-scheme` | ok |
| 12 | Сборка APK, UI-классы (`OptionPicker`, `MainActivity`, `SellLauncher`, `ReceiptPrintExtraService`, `ConfigOptsMapper`), печать на терминале | **не проверено** — в окружении нет Android SDK/Gradle |

## Допущения и ограничения
- Эти файлы вставлены по якорям, которые совпали с исходниками дословно; они не компилировались. Сначала нужна сборка Gradle и `./gradlew testDebugUnitTest`.
- Комбо-набор показывает выбор только если конфигурация товара уже загружена (так же работает молоко). Каталожный `choiceSchemeId` APK пока не использует.
- Если лист товара открыт до прихода конфигурации, он пересобирается при её получении (как для молока).
- Админка: SSR-теста страницы нет (в проекте их нет); проверены логика черновика и сборка.
- Тестовые `node_modules` и `dist` из архивов убраны; `package-lock.json` не менялся.

## Проверить на терминале
1. Напиток с корицей: нельзя добавить, пока не выбран вариант; сумма не меняется.
2. Бумажный чек: под «ЗАКАЗ № …» напечатана строка вроде «Капучино — Корица: С корицей»; ширина ленты не ломает перенос.
3. Электронный чек/ОФД выбора не содержит (ожидаемо).
4. Напиток без схемы и эспрессо добавляются как раньше.
5. Отмена оплаты не оставляет строку выбора на следующем чеке.

## Порядок выкладки
backend + админка → APK 159 на все кассы → только потом создать и назначить схему «Корица» (иначе старый APK пропустит выбор).

**Готово к заливке:** backend + админка — да. APK — **нет**, пока не собран и не проверен на терминале.
