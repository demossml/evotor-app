# 4.6.9-toppings-upsell (versionCode 84)

## Почему «ничего не менялось» с добавками
Лист добавок открывался **только если** у хотя бы одной добавки был `evotorUuid`.
Если backend отдал схему без uuid — sheet **не показывался**, товар сразу в корзину.

## Исправления
1. Sheet добавок открывается при **любой** непустой схеме (`toppings.isNotEmpty()`).
   Строки без uuid по-прежнему «нет в кассе Эвотор» и не уходят в чек.
2. Рекомендации перед оплатой: рабочие **− / N / +** (как на плитке), `onCartChanged`, счётчик обновляется.
3. Resolve uuid рекомендации: `evotorUuid` → иначе productId из enrich → иначе имя в меню.
4. Logcat `SixthCup`: `withToppings=… sellableToppings=… upsellDefault=…` после enrich.

## Если withToppings=0 в логе
Схемы не приходят с `/api/devices/catalog` — чинить backend/админку (modifier scheme + связи), не только APK.
