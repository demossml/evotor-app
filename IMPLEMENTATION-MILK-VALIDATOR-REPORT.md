# IMPLEMENTATION-MILK-VALIDATOR-REPORT

Исходники: `evotor-app-main_10.zip` (versionCode 157) и `sixth-cup-main_11.zip`. Версия APK: **158 / 5.2.32-milk-validator**.

## 1. Что исправлено
Платное молоко (из схемы молока) больше не сверяется с добавками каталога в `ReceiptValidator`: его `optionId` — это `milk_scheme_variants.id`, а не id добавки, поэтому раньше проверка либо не находила id (`option_removed`), либо находила чужую добавку и давала «Добавка изменилась в админке». Строки молока помечены флагом `fromMilkScheme`. Добавлена защита от неверной привязки «название варианта ↔ товар» (backend + админка, только предупреждения).

## 2. Файлы
APK (`app/src/main/java/ru/sixthcup/evotor/`): `data/InventoryRepository.kt` (Topping.fromMilkScheme), `ui/OptionPicker.kt` (Opt.toTopping), `data/DrinkConfig.kt`, `data/Cart.kt` (поле + сохранение `ms`), `data/CartLines.kt`, `ui/MainActivity.kt` (комбо-путь), `data/ReceiptValidator.kt` (главная правка), `app/build.gradle.kts` (158). Тесты: `ReceiptValidatorTest.kt` (+4), новый `MilkLineFlagTest.kt` (4). Доки: `docs/DYNAMIC-DRINK-CONFIGURATION.md`.
Backend: `modules/catalog/milkScheme.ts` (milkLabelMatches, milkLabelWarnings), `modules/admin/routes.ts` (`warnings` в POST, `GET /milk-schemes/audit`), `__tests__/milk-scheme.test.ts` (+3), `docs/MILK-OPTION-IDS.md`.
Admin: `lib/milkScheme.ts`, `lib/milkScheme.test.ts` (+4), `pages/MilkSchemes.tsx`.
Миграций нет. Формат `/configuration` не менялся.

## 3. Результаты прогона
- Backend: vitest **88 файлов / 584 теста** pass, `tsc --noEmit` чисто.
- Admin: vitest **10 файлов / 54 теста** pass, `tsc` чисто, `vite build` OK (dist удалён).
- APK: **Gradle/Android SDK недоступны — APK не собирался, `testDebugUnitTest` не запускался.** Чистая логика (ReceiptValidatorTest, MilkLineFlagTest, DrinkConfigTest + проверка сохранения/чтения корзины `ms`) скомпилирована `kotlinc` 2.0.21 с заглушками Android/org.json и JUnit: **32/32 pass**. Без правки A7 два новых теста про молоко падают (проверено).
- Не проверялось: сборка APK, поведение на терминале, `OptionPicker.kt`/`MainActivity.kt` на уровне компиляции Android (правки — по одной строке с `key.startsWith("c:m:")`).

## 4. Чек-лист приёмки
| # | Проверка | Итог |
|---|---|---|
| 1 | Молоко с id-коллизией не даёт option_changed | ok (тест) |
| 2 | Молоко без id в каталоге не даёт option_removed | ok (тест) |
| 3 | Молоко без товара на точке → product_not_found | ok (тест) |
| 4 | Добавки валидируются как раньше | ok (прежние + новый тест) |
| 5 | Обычное молоко не создаёт строк чека | ok (MilkLineFlagTest) |
| 6 | Флаг: Opt → Topping → Line → CartLine; Cart encode/decode; старый JSON = false | ok |
| 7 | Комбо-путь ставит флаг по key | ok по коду (не запускался) |
| 8 | `/configuration`: формат и id молока прежние | ok (тест) |
| 9 | POST /milk-schemes → warnings; кейс кокос↔овсяное = 1 | ok (тест) |
| 10 | GET /milk-schemes/audit | ok (тест) |
| 11 | Admin: предупреждение, «Сохранить» не блокируется; vitest/tsc/build | ok (тесты; UI визуально не смотрел) |
| 12 | versionCode 158 / 5.2.32-milk-validator | ok |
| 13 | Регрессии (весь backend/admin набор) | ok; APK-регрессии — только перечисленные тесты |
| 14 | Архивы без node_modules/dist/build, структура как у исходников | ok |

## 5. ASSUMPTIONS
1. Актуальность молока на терминале проверяет существующий `sendReceipt` (lookup по uuid), валидатор — только наличие товара в каталоге точки.
2. Предупреждение о названии — эвристика по основам слов; возможны ложные срабатывания (например «Безлактозное» ↔ «Лактоза-фри»), сохранение не блокируется.
3. `fromMilkScheme` определяется по ключу `c:m:` (его строит `ConfigOptsMapper.milk`); легаси-молоко из каталога (`t:<id>`) валидируется как раньше.

## 6. Для владельца (агент не выполнял)
Данные не менялись. Проверка (read-only):
```sql
SELECT v.id, v.scheme_id, v.label, v.product_id, p.name FROM milk_scheme_variants v LEFT JOIN products p ON p.id=v.product_id WHERE v.send_to_receipt=1 AND v.active=1 ORDER BY v.scheme_id, v.sort_order;
SELECT o.id, o.group_key, o.product_id FROM modifier_scheme_options o WHERE o.id IN (SELECT id FROM milk_scheme_variants WHERE send_to_receipt=1);
```
Исправление — через админку: Меню → Молоко → схема → у «Кокосовое молоко» товар «Молоко кокосовое» (66) → Сохранить; предупреждение должно исчезнуть.
Проверка на кассе после установки 158: Капучино 350 + «Кокосовое молоко» → «В чек»: диалога нет, в чеке напиток + «Молоко кокосовое»; «Обычное молоко» — только напиток.

готово к заливке: **backend + admin — да; APK — не собирался (нет Android SDK), собрать и прогнать `./gradlew testDebugUnitTest assembleDebug` перед установкой.**
