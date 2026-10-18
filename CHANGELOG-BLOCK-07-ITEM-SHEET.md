# Block 07 — item sheet (index → Android)

Источник: index `#itemOverlay` / `.item-hero` / `.item-intro` / `.item-foot` / `#addItemBtn`.

## Что сделано
- Hero 128dp, тёплый градиент, фото, flag ХИТ/КОМБО, close ✕
- Intro: name 20 + base price 17, recipe как desc, tags (group / 6.7)
- Опции: группы toppings с backend (`groupKey`), toggle +/✓
- Непродаваемые (без evotorUuid): «нет в кассе Эвотор», disabled
- Foot: qty stepper + «В чек · sum» (live total)
- В корзину: прежняя логика cartKey `#mods:` + отдельные позиции топпингов с uuid

## Не трогали
- rec sheet / pay sheet
- SellLauncher / loyalty / Inventory
