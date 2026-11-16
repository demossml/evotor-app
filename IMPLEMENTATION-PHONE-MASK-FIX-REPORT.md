# PHONE MASK FIX — REPORT

## Версия

- `versionCode`: **172**
- `versionName`: **5.3.13-phone-mask-fix**
- Тесты: **640 / 0**
- APK: `app/build/outputs/apk/release/app-release.apk`

## Root cause (2 предложения)

`PhoneUtil.format()` каждый раз разбирал уже отформатированный display и считал `7` из префикса `+7` национальной цифрой; когда цифр становилось 10, он добавлял ещё одну `7` и пересобирал номер. `TextWatcher` пересчитывал маску из этого испорченного display после каждого символа и после backspace, поэтому лишняя `7` попадала в код оператора, а цифры «съезжали».

## До / после

### До

Полный display:

```text
+7 (999) 123-45-67
```

Один backspace:

```text
+7 (999) 123-45-6
→ digits: 7999123456 (10)
→ format() добавляет ещё 7
→ +7 (799) 991-23-45
```

Из-за этого нельзя было нормально ввести последние две цифры: на 10-й цифре срабатывал `prepend(7)`.

### После

Храним только национальные 10 цифр, без кода страны:

```text
+7 (999) 123-45-6
→ nationalDigits: 999123456
→ display: +7 (999) 123-45-6
```

Полный номер:

```text
nationalDigits: 9991234567
formatDisplay:  +7 (999) 123-45-67
toApiDigits:    79991234567
```

## Что изменено

- `PhoneUtil`:
  - `nationalDigits()` — единственный источник истины, 10 цифр без `+7`;
  - `formatDisplay()` — маска только из national digits;
  - `toApiDigits()` — `7` + 10 цифр;
  - `isComplete()`;
  - `normalize()`, `isValid()`, `format()`, `masked()` сохранены для совместимости.
- `MainActivity`, только поле телефона в `showPhoneCardSheet`:
  - `afterTextChanged` с защитой от рекурсии;
  - маска строится из `nationalDigits`, а не из повторного разбора display;
  - курсор в конце;
  - lookup/register получают `toApiDigits(nationalDigits(...))`.
- `PhoneUtilTest`:
  - посимвольный ввод `8 → 9991234567`;
  - backspace с полного номера;
  - лишняя цифра после полного номера;
  - идемпотентность `formatDisplay(nationalDigits(display))`;
  - парсинг `+7`, `8`, `7` и частичного `+7 (999`.

## Не делал

- SMS;
- backend;
- изменение процента начисления;
- рефакторинг `MainActivity` вне phone sheet;
- изменения `CustomersApi`, `SellLauncher`, бонусной логики.
