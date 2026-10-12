# 4.4.0-pos-ui (versionCode 71)

## UI как на целевых скринах (5″ vertical)
- Чипы категорий «Всё» + группы Эвотор — pill, active `#002FA7`.
- Блок **Фокус**: горизонтальные карточки (featured `#focus` / комбо с `+` в названии / иначе напитки).
- Список: строка с круглым thumb (инициалы), название, цена, meta; «i» = рецепт.
- Фон страницы `#F5F7FB`, карточки белые, меньше обрезки по краям (clip/padding на чипах).

## Фото
- Пока **плейсхолдеры** (инициалы на brand_soft): в ProductTable Эвотор нет стабильного URL картинки в текущем integration path.
- Чтобы закатить реальные миниатюры: админка → imageUrl → sync в описание/`#img:` или отдельный API; на клиенте Coil + WebP 128/256 px. Структура UI уже готова под ImageView.

## Не ломали
- Цены KOPECKS/100, free-cup rules, loyalty resolve, extras.sc, SellLauncher, version only up.

## Сборка
./gradlew :app:assembleDebug
