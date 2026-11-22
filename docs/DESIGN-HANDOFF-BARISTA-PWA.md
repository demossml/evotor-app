# DESIGN HANDOFF → PWA «Бариста» (дизайн-система «Steel»)

**Кому:** агенту, который делает редизайн нашего прогрессивного веб-приложения и новый экран бариста.
**Что это:** самостоятельный дизайн-спек. Ссылки на исходное приложение не нужны — все размеры, токены и правила выписаны здесь и получены замерами на реальном устройстве (390 × 844 CSS px, DPR 3, touch).
**Что можно менять:** внешний слой — токены, геометрию, типографику, движение, композицию.
**Что нельзя менять:** бизнес-логику, контракты и механики нашего продукта (раздел 1).

---

## 1. Жёсткие ограничения (читать до первой строки кода)

Это **редизайн, а не переписывание**. Ни один пункт ниже не может быть «оптимизирован» по ходу работы.

1. **Контракты не трогаем.** `extras.sc` (`v:2, c, op, free, cb, ts, kind`) + дубль `<APP_UUID>.sc`, `evotorUuid` добавок, `OpenSellReceiptCommand`, `ReceiptValidator`, `FreeCupRules`, `queueShowPending`, `PENDING → CONFIRMED → READY`, TTL 10 мин.
2. **Оплата не наша.** В APK оплату и чек делает Эвотор POS. Никаких «Оплата прошла», «Сдача», выбора способа оплаты в нашем APK. Для экрана бариста оплата не нужна вообще.
3. **Каталог — только инвентарь терминала.** Никаких локальных демо-товаров, «примеров» и фолбэк-каталогов.
4. **Ничего не удаляем.** Стоп-лист, поиск, pull-to-refresh, «Ещё», подарок, кэшбэк, ручной ввод кода, «продолжить без карты» — всё остаётся.
5. **Любой новый компонент имеет безопасное пустое состояние**: нет данных → выглядит как сейчас (текст + действие), но в новой обёртке.
6. **Один PR = один экран.** Не переписываем навигацию и не тащим глобальный рефакторинг вместе с дизайном.
7. **Регресс-чеклист** из `docs/BARISTA-FLOW-V2.md` (10 пунктов + добавки) проходит после каждой фазы.

Если требование дизайна конфликтует с пунктом 1–7 — **выигрывает пункт 1–7**, дизайн адаптируется.

---

## 2. tokens.css (можно вставлять как есть)

```css
/* ============================================================
   Steel design tokens
   Значения замерены на 390×844. Всё остальное выводится отсюда.
   ============================================================ */
:root {
  /* ---------- Цвет: текст ---------- */
  --c-fg:            #000000;
  --c-fg-ink:        #07072D;
  --c-fg-secondary:  #555555;
  --c-fg-tertiary:   #AFB8CF;
  --c-fg-muted:      #6A6A72;   /* время, служебное */
  --c-fg-meta:       #7A7F8F;   /* подписи под заголовком */
  --c-fg-red:        #DC2E4C;
  --c-fg-blue:       #2A55D8;
  --c-fg-invert:     #FFFFFF;

  /* ---------- Цвет: поверхности ---------- */
  --c-bg:            #FFFFFF;
  --c-paper:         #FAFAFA;   /* листы, шторки, cart bar */
  --c-sheet:         #F4F4F6;   /* страница меню, экран оплаты */
  --c-app:           #000000;   /* фон приложения */
  --c-blue:          #2A55D8;   /* главная кнопка */
  --c-blue-ring:     #3763E6;   /* кольцо выбранного чипа */
  --c-blue-soft:     #EEF2FD;   /* круг под иконкой */
  --c-pink:          #FF4D6A;   /* корзина / оплата */
  --c-pink-soft:     #F8D5DC;
  --c-green:         #66DF66;   /* «Готово» */
  --c-green-dark:    #2FB52F;
  --c-yellow:        #FFE03A;   /* «Ошибка» */
  --c-red:           #E5191F;   /* стоп-лист */
  --c-line:          #F0F0F5;   /* рамки/кольца/разделители */
  --c-line-strong:   #DFE2EA;   /* разделитель между строками */
  --c-chart:         #AFB8CF;
  --c-chart-now:     #F35E5E;

  /* ---------- Радиусы ---------- */
  --r-outer:   40px;   /* экранные поверхности: меню, листы, корзина */
  --r-ctx:     30px;   /* контекстная карточка */
  --r-card:    16px;   /* карточки «Фокус» */
  --r-inner:   28px;   /* виджеты/плитки = outer − 12 */
  --r-pill:    999px;
  --r-circle:  50%;
  --sq-k:      1;      /* 1 по умолчанию; 1.75 включается только при поддержке squircle (§5) */

  /* ---------- Типографика ---------- */
  --font-ui: "Inter", "Inter Variable", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  --font-hand: "Caveat", "Segoe Script", cursive;

  --fs-xs:   0.75rem;    /* 12 */
  --fs-sm:   0.8125rem;  /* 13 */
  --fs-tag:  0.875rem;   /* 14 */
  --fs-body: 1.0625rem;  /* 17 — метаданные, метки */
  --fs-lead: 1.1875rem;  /* 19 — названия, цены, чипы */
  --fs-xl:   1.5rem;     /* 24 — заголовок секции */
  --fs-title:1.75rem;    /* 28 — название товара в листе */
  --fs-h1:   2rem;       /* 32 — заголовок экрана */
  --fs-display: clamp(2.25rem, 11vw, 3rem); /* 36→48 */
  --fs-hero:    clamp(2.5rem, 9vw, 4rem);   /* сумма оплаты */

  /* ---------- Сетка ---------- */
  --gutter: clamp(10px, 3.4vw, 16px);  /* на 390 = 13.26px */
  --gap-1: 2px;
  --gap-2: 4px;
  --gap-3: 6px;
  --gap-4: 8px;
  --gap-5: 10px;
  --gap-6: 12px;
  --pad-card: 16px;
  --pad-screen-x: 4px;      /* рамка листа */
  --safe-top: env(safe-area-inset-top, 0px);
  --safe-bottom: env(safe-area-inset-bottom, 0px);

  /* ---------- Тени ---------- */
  --shadow-btn:    0 2px 12px rgba(0, 0, 0, .10);
  --shadow-card:   0 1px 3px rgba(7, 7, 45, .06);
  --shadow-deck:   0 4px 20px rgba(0, 0, 0, .10), 0 4px 12px rgba(0, 0, 0, .15);
  --shadow-sheet:  0 8px 32px rgba(0, 0, 0, .15);
  --shadow-float:  0 6px 24px rgba(7, 7, 45, .12), 0 1px 3px rgba(7, 7, 45, .08);
  --shadow-sticker:0 1px 1px rgba(10, 40, 100, .14), 0 12px 14px -10px rgba(10, 30, 80, .36);

  /* ---------- Движение ---------- */
  --ease-out: cubic-bezier(.23, 1, .32, 1);      /* микро */
  --ease-ios: cubic-bezier(.32, .72, 0, 1);      /* листы, страницы, стек */
  --ease-goo: cubic-bezier(.55, 0, .25, 1);      /* жидкость */
  --ease-fb:  cubic-bezier(.2, .9, .25, 1);      /* фильтры */
  --t-tap:     120ms;
  --t-chip:    200ms;
  --t-toast:   260ms;
  --t-sheet:   320ms;
  --t-stack:   400ms;
  --t-cart:    380ms;
  --t-page:    540ms;
  --t-drawer:  440ms;
  --t-goo:     620ms;

  color-scheme: light;
}

/* Глобально */
html, body {
  margin: 0;
  height: 100%;
  background: var(--c-app);
  overscroll-behavior: none;
  -webkit-text-size-adjust: 100%;
}
body {
  font-family: var(--font-ui);
  color: var(--c-fg);
  font-optical-sizing: none;
  -webkit-font-smoothing: antialiased;
}
*, *::before, *::after { box-sizing: border-box; }

/* Числа всегда табличные */
.num { font-variant-numeric: tabular-nums; }

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: .01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: .01ms !important;
  }
}
```

---

## 3. Типографика

### 3.1 Шрифты

| Роль | Семейство | Начертания | Подключение |
|---|---|---|---|
| Интерфейс | **Inter** (variable) | 400 / 500 / 600 (редко 300, 700) | `family=Inter:opsz,wght@14..32,300..700&display=swap`, `preconnect` к `fonts.gstatic.com` |
| Рукописные заметки | **Caveat** | 500 | `family=Caveat:wght@500` |
| Фолбэк | `system-ui, -apple-system, "Segoe UI", Roboto` | — | — |

Правило: **вес 500 — норма для акцента**, 600 — только заголовки, 700 — одна-две цифры (таймер).

### 3.2 Шкала и применение

| Роль | Размер / интерлиньяж | Вес | Трекинг | Пример |
|---|---|---|---|---|
| Микроподпись | 12 / 1.25 | 400 | 0 | «Демо-код» |
| Служебное | 13 / 1.25 | 400 | 0 | «1 позиция» |
| Тег | 14 / 16 | 400 | −0.21px | `1 шт.`, `Арабика` |
| Пояснение | 15 / 20 | 400 | 0 | «Сумма уже на терминале…» |
| Метаданные | 17 / 20 | 400 | −0.255px | `250 мл 95 Ккал …` |
| Название/цена в списке | 19 / 24 | 400 / **500** (цена) | 0 | «Американо … 120 ₽» |
| Чип, текст карточки | 19 / 24 | 400 | 0 | «Арабика» |
| Акцентная сумма | 21 / 24 | 500 | 0 | сумма в cart bar |
| Заголовок секции | 24 / 1.33 | 500 | −0.48px | «Кофе» |
| Заголовок страницы | 24 / 1.2 | 600 | −0.48px | «Оплата» |
| Название товара в листе | 28 / 32 | 500 | 0 | «Эспрессо» |
| Заголовок экрана | 32 / 38 | 600 | −0.02em | «Открытие: Красная зона» |
| Крупный заголовок карточки | clamp(36, 11vw, 48) / 1.05 | 500 | −0.02em | «Включить кофемашину» |
| Кнопка цены / оплаты | 33–36 / 44 | 400 | 0…−1.44px | «100 ₽» |
| Сумма на оплате | clamp(40, 16vw, 64) / 1.1 | 500 | 0 | «220 ₽» |
| Таймер | clamp(1.75rem, 8.5vw, 2.25rem) | 700 | −0.02em | «−29:32» |

Дополнительно:
- крупные заголовки — `text-wrap: balance`;
- числа — `font-variant-numeric: tabular-nums` (обязательно для времени, сумм, счётчиков);
- минимальный размер **12 px**, ниже не опускаемся;
- строка текста — не длиннее `~30ch` в карточках.

---

## 4. Цвет и семантика

| Роль | Токен | HEX | Где использовать | Не использовать |
|---|---|---|---|---|
| Фон приложения | `--c-app` | `#000` | под всеми слоями | для текста |
| Бумага | `--c-paper` | `#FAFAFA` | листы, шторки, cart bar | как фон всего экрана |
| Лист | `--c-sheet` | `#F4F4F6` | страница меню, экран оплаты | для карточек |
| Карточка | `--c-bg` | `#FFF` | карточки, кнопки, чипы | как фон экрана |
| Акцент действия | `--c-blue` | `#2A55D8` | главная кнопка, активная вкладка | для текста-ссылки (там `--c-fg-blue`) |
| Акцент выбора | `--c-blue-ring` | `#3763E6` | кольцо выбранного чипа | заливка |
| Деньги/корзина | `--c-pink` | `#FF4D6A` | «Оплатить», сумма корзины | для ошибок |
| Успех | `--c-green` | `#66DF66` | «Готово», тост успеха | для текста на белом (контраст) |
| Внимание | `--c-yellow` | `#FFE03A` | «Ошибка», предупреждение | как фон текста без чёрного текста |
| Опасность | `--c-red` | `#E5191F` | стоп-лист, критичное число | большие площади |
| Метаданные | `--c-fg-tertiary` | `#AFB8CF` | объём, Ккал, старая цена | основной текст |
| Разделитель | `--c-line-strong` | `#DFE2EA` | линия между строками | рамка карточки |

**Правила:**
- Один акцент на экран. Если на экране синяя кнопка — розовая сумма не появляется, и наоборот.
- Никаких градиентов, кроме аватара и специальных часов.
- Контраст текста ≥ 4.5:1 для основного, ≥ 3:1 для крупного.
- Цвет не единственный носитель смысла: статус дублируется словом/иконкой.

### 4.1 Бренд-оверлей (если нужно сохранить фирменный стиль 6.7)

Дизайн-система параметризована. Достаточно переопределить 4 переменные — геометрия и движение останутся «Steel»:

```css
[data-brand="67coffee"] {
  --c-app:       #17140F;  /* вместо чёрного — тёмный ink */
  --c-sheet:     #F6F3EE;  /* кремовый лист */
  --c-paper:     #FBF9F5;
  --c-blue:      #002FA7;  /* фирменный синий */
  --c-blue-ring: #1A6EFF;
}
```

Тёплые поверхности требуют пересчёта `--c-fg-tertiary` (взять `#8A8377`) — иначе холодный серый «спорит» с кремовым.

---

## 5. Радиусы и правило концентричности

| Токен | Значение | Применение |
|---|---|---|
| `--r-outer` | 40 | страница меню (верх), лист товара, корзина, колода |
| `--r-inner` | 28 | виджеты, плитки, миниатюры (= 40 − 12) |
| `--r-card` | 16 | карточки «Фокус» |
| `--r-ctx` | 30 | контекстные карточки |
| `--r-ctx-card` | 20 | карточка long-press |
| `--r-pill` | 999 | все кнопки, чипы, теги |
| `--r-circle` | 50 % | иконки-кнопки, точки |

**Правило концентричности (обязательно):**
```
радиус_внутреннего = радиус_внешнего − отступ
```
Пример: лист 40, внутренний блок с отступом 8 → 32; плитка 40 − 12 = 28; кнопка 64 в листе 70 → 32.

**Squircle.** На крупных поверхностях используем «сквиркл» вместо круга;

```css
@supports (corner-shape: squircle) {
  :root { --sq-k: 1.75; }
  .sheet, .shelf, .deck-card, .focus-card { corner-shape: squircle; }
  .sheet  { border-radius: calc(var(--r-outer) * var(--sq-k)); } /* 40 → 70 */
  .focus-card { border-radius: calc(var(--r-card) * var(--sq-k)); } /* 16 → 28 */
  .widget, .tile { border-radius: calc(var(--r-inner) * var(--sq-k)); } /* 28 → 49 */
}
```
Без поддержки — обычные круглые радиусы, композиция не меняется. **Пилюли и круги не трогаем никогда.**

---

## 6. Сетка, отступы, safe-area

| Параметр | Значение |
|---|---|
| Желоб по бокам | `clamp(10px, 3.4vw, 16px)` → 13.26 на 390, 16 на 520 |
| Верхний отступ контента | `safe-top + 8px` |
| Нижний отступ списка | `150px + safe-bottom` (под плавающую навигацию) |
| Рамка листа | 4 px со всех сторон |
| Паддинг «бумаги» | 24 / 28 px (верх/низ и бока) |
| Карточка | 16 px |
| Строка списка | `10px 2px`, gap 12 px |
| Зазоры | 2 / 4 / 6 / 8 / 10 / 12 px — по смыслу, не по сетке |

**Ритм:** 4 — внутри пилюли; 6 — между виджетами; 8 — между блоками; 12 — между крупными; 16 — паддинг бумаги; 24–28 — внутри листов.

**Тач-таргеты:** минимум 44 × 44, фактически в системе 56–88:
кнопки 64, чипы 60, клавиши 85,8, нижние вкладки 80 × 56, круглые действия 88.

---

## 7. Движение

### 7.1 Токены

| Токен | Кривая |
|---|---|
| `--ease-out` | `cubic-bezier(.23, 1, .32, 1)` |
| `--ease-ios` | `cubic-bezier(.32, .72, 0, 1)` |
| `--ease-goo` | `cubic-bezier(.55, 0, .25, 1)` |
| `--ease-fb` | `cubic-bezier(.2, .9, .25, 1)` |

### 7.2 Длительности по событиям

| Событие | Мс | Кривая | Свойства |
|---|---|---|---|
| Нажатие кнопки | 120 | ease-out | `transform: scale(.96)` |
| Выбор чипа | 200 | ease-out | `border-color`, `box-shadow` |
| Тост приход/уход | 260 / 180 | ease-ios / ease-in | `opacity`, `transform` |
| Лист товара | 320 | ease-ios | `transform`, `opacity` |
| Меню под листом | 320 | ease-ios | `scale(.94)`, `filter: blur(6px)` |
| Стопка копий | 400 | ease-ios | `transform` |
| Корзина открыть | 380 | ease-ios | `transform` |
| Корзина закрыть | 280 | ease-ios | `transform` |
| Страница виджета | 540 / 380 | ease-ios | `transform`, `opacity` |
| Drawer | 440 | ease-ios | `transform` |
| Перелив в корзину | 620 | ease-goo | blob + drops |
| Одометр цены | 280 | ease-ios | `translateY` колонок |
| Смена задачи | 380 | ease-ios | flip |
| Ошибка PIN | 300 | linear | `translateX ±4px` |

### 7.3 Базовые keyframes

```css
@keyframes fade      { from { opacity: 0 } }
@keyframes slide-up  { from { transform: translateY(32px); opacity: 0 } }
@keyframes pop       { from { transform: scale(.9); opacity: .4 } }
@keyframes toast-in  { from { transform: translateY(16px) scale(.96); opacity: 0 } }
@keyframes toast-out { to   { transform: translateY(8px) scale(.98); opacity: 0 } }
@keyframes shake     { 25% { transform: translateX(-4px) } 75% { transform: translateX(4px) } }
@keyframes pulse     { 50% { transform: scale(1.04) } }
```

### 7.4 Три фирменных эффекта, которые дают «дорогое» ощущение

**1. Одометр суммы.** Каждая цифра — колонка 0–9, сдвиг по `--d`:

```css
.odo { display: inline-flex }
.odo__digit { display: inline-block; height: 1lh; overflow: hidden;
  mask-image: linear-gradient(transparent, #000 14%, #000 86%, transparent); }
.odo__col { display: flex; flex-direction: column;
  transform: translateY(calc(var(--d) * -10%));
  transition: transform 280ms var(--ease-ios); }
.odo__col > span { height: 1lh; text-align: center }
```

**2. Перелив в корзину (goo).** Лист превращается в каплю и «всасывается» в нижнюю кромку панели корзины. Реализуется SVG-фильтром (blur + повышение альфы), который накладывается на слой с каплей и кромкой:

```html
<svg width="0" height="0" aria-hidden="true">
  <filter id="goo" color-interpolation-filters="sRGB">
    <feGaussianBlur in="SourceGraphic" stdDeviation="11" result="b"/>
    <feColorMatrix in="b" values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 22 -9"/>
  </filter>
</svg>
```
Капля: 4 keyframe-состояния (лист → сжатие → узкая струя → всасывание), `620 ms`, `ease-goo`. Капли 34/26/18 px, `420 ms`, задержки 160/230/300. Кромка/панель вспухает на 6 px и садится. **Включать только при `prefers-reduced-motion: no-preference` и не на слабых устройствах.**

**3. Колода карточек.** Нижние карты — `translateY(step*8px) scale(1 − step*.03)`, `transform-origin: 50% 100%`, `340 ms` `ease-ios`; уходящая карта смахивается с наклоном `translateX(-26%) rotate(-7deg)`.

---

## 8. Компоненты: анатомия и размеры

Все значения — CSS px при ширине 390. При других ширинах: `--gutter` меняется, остальное фиксировано.

### 8.1 Каркас экрана

```
.app      : 100dvh, overflow hidden, background var(--c-app), max-width 520, margin auto
.screen   : position absolute; inset 0; display flex; column
.shelf    : страница меню — absolute; top 82px; radius 70 70 0 0; background var(--c-sheet)
```
Десктоп (≥ 600 px): телефон-макет `max-width: 520px; height: min(100dvh − 32px, 940px); border-radius: 40px; box-shadow: 0 0 0 10px #0B0B0D, 0 30px 80px rgba(0,0,0,.6)`.

### 8.2 Верхняя строка виджетов

| Параметр | Значение |
|---|---|
| Высота | 56 |
| Отступы | 8 px сверху (`safe-top + 8`), 16 px по бокам |
| Gap | 6 |
| Кнопка-иконка | 56 × 56, r 31,5, фон `#FFF`, border 1 px `#F3F4F5` |
| Контекстный виджет | flex: 1, 56 высота, r 31,5, паддинг 0 14, две строки: надпись 13–17 и значение |
| Часы | 118 × 56, r 31,5, цифры 21/24 вес 600, гистограмма 4–6 столбцов 22 max ширины, r 6 6 3 3 |
| Нажатие | `scale(.96)` 120 ms |

### 8.3 Заголовок секции

```
font: 500 24px/1.33 var(--font-ui);
letter-spacing: −0.48px;
margin: 24px 0 8px;
```

### 8.4 Строка товара (основной элемент каталога)

```
height: 74px; padding: 10px 2px; display: grid;
grid-template-columns: 72px 1fr auto; column-gap: 12px; align-items: center;
border-radius: 16px;
```
| Часть | Стиль |
|---|---|
| Картинка | 72 × 72, `object-fit: contain`, без маски |
| Название | 19/24, 400, обрезка `…` в одну строку |
| Цена | 19/24, **500**, справа |
| Метаданные | 17/20, `#AFB8CF`, `−0.255px`, строка: `250 мл · 95 Ккал · 5,4 Б · 4 Ж · 8,5 У` |
| Разделитель | 1 px `#DFE2EA`, слева 64 px (под текстом), справа 4 px |
| Нажатие | фон `rgba(0,0,0,.03)` |
| Недоступен | `opacity: .45` |

### 8.5 Карточка «Фокус»

```
width: 185 (2 колонки при 390 с gutter 13.26 и gap 8);
border-radius: 28 (squircle);
background: #FFF; padding: 2px;
box-shadow: 0 1px 3px rgba(7,7,45,.06);
```
Внутри: картинка (contain, ~84 px), название 19/24, цена: старая — `#AFB8CF` + `line-through`, новая — `--c-pink`. Комбо — две картинки в одной карточке с «бумажным» стыком (радиус 8 по внутренним углам).

### 8.6 Нижняя навигация

```
width: 256; height: 72; padding: 8; border-radius: 999;
background: rgba(255,255,255,.8);
backdrop-filter: blur(16px);
box-shadow: inset 0 0 0 1px #F0F0F5, 0 4px 12px rgba(0,0,0,.08);
bottom: 6px + safe-bottom; left 0; right 0; margin 0 auto;
```
Вкладка: 80 × 56, r 999; активная — фон `--c-blue`; иконка 52 × 52 (две иконки крест-накрест по `opacity`, переход 200 ms). При скролле вниз панель «сжимается» (высота 56, иконки 44) — но не исчезает.

### 8.7 Пилюля фильтров

```
height: 60; border-radius: 999; background: #FFF;
padding: 0 8px; display: flex; align-items: center; gap: 8px;
font: 400 19px/24px;
box-shadow: 0 1px 2px rgba(0,0,0,.06);
```
Внутри иконка «меню» 24–32 и текст. Рядом — «Сбросить» (19 px, без фона), появляется только когда есть выбранные фильтры.

### 8.8 Лист товара (SKU sheet)

```
.sheet        : width 374 (390 − 2×8); border-radius 70 70 0 0 (squircle), фон #FAFAFA
.sheet__head  : height 76; padding 4 4 0; display flex; gap 12; align-items center
.sheet__head img : 64 × 64
.sheet__title : font 500 28/32; padding 8 12
.icon-btn     : 64 × 64; radius 50%; background #FFF; box-shadow var(--shadow-btn)
.sheet__body  : padding 4 12 13
.sheet__foot  : height 80; padding 4; display flex; gap 8
```
Группа опций:
```
.group__label : 17/20, gap 10; номер — круг 28 × 28, кольцо 1px #AFB8CF, цифра 17, цвет #909AB3
.chips        : flex wrap; padding 4; gap 0 (чип сам даёт 2px margin → 4px между)
.chip         : height 60; padding 8 16; radius 999; background #FFF;
                border: 2px solid #F0F0F5
.chip.is-selected : border-color #3763E6 + inset 0 0 0 1px #3763E6
                    (рамка остаётся 2px — выбранное состояние не сдвигает соседей)
.chip small   : 14/16, цена-дельта (+50 ₽)
```

Низ листа:
| Кнопка | Размер | Стиль |
|---|---|---|
| Заметки (карандаш) | 64 × 64 | белый круг |
| Цена | 198 × 64 | `--c-blue`, белый текст 33,5/44, иконка корзины 44 |
| `+1` | 64 × 64 | белый круг, текст 24/500 |

Состояние «несколько копий»: цена превращается в две колонки — `90 ₽ ×2 шт` слева, сумма справа.
Состояние «редактирование»: текст «Обновить» + дельта `+50 ₽`/`−20 ₽`; disabled, пока `itemKey` не изменился.

### 8.9 Панель корзины (cart bar)

```
height: 74.7; padding: 12; border-radius: 0 0 70 70;
background: #FAFAFA; display: flex; gap: 6; align-items: center;
```
| Часть | Стиль |
|---|---|
| Миниатюры | 50,7 × 50,7, r 28, фон `#FFF`, паддинг 4, наложение −8 px, при qty > 1 — бейдж-точка |
| Итого | пилюля высотой 50,7, padding 0 22, r 999, фон `--c-pink`, текст 21/500 белый |
| Размер шрифта итога | `clamp(18px, 5.4vw, 21px)` |

### 8.10 Шторка корзины

```
.cartsheet : сверху; border-radius 0 0 40 40; background #FAFAFA; padding 4;
             box-shadow var(--shadow-sheet)
.head      : height 72; padding 4 4 4 0; display flex; align-items center; justify-content space-between
.title     : 27,3/32 · 500; номер заказа — серым #AFB8CF
.sec-btn   : height 64; padding 0 24; radius 999; background #FFF; font 24
.line      : height 116; padding 2; display flex; gap 4
.line img  : 68 × 68
.tags      : gap 4; .tag — высота 32; padding 6 8; radius 999; background #FFF; font 14/16
.pay       : 374 × 64; radius 999; background var(--c-pink); font 36/44; letter-spacing −1.44
```
Тап по строке — редактирование (тот же лист), корзина-иконка — удалить (`vibrate(30)`).

### 8.11 Колода карточек (открытие смены / очередь бариста)

```
.deck        : display flex column; background #000
.head h1     : 32/38 · 600; padding 12 gutter 16
.stack       : flex 1; margin 0 gutter
.card        : position absolute; inset 0 0 24px; border-radius 40 (squircle);
               background #FFF; border 2px #F0F0F5; box-shadow var(--shadow-deck);
               transform: translateY(step*8px) scale(1 − step*.03);
               transform-origin: 50% 100%; transition: transform 340ms var(--ease-ios)
.card h2     : clamp(36px, 11vw, 48px)/1.05 · 500 · −0.02em; margin-top auto
.card meta   : 17/20 #6B7390
.actions     : height 108; padding 12 0 8; gap 16
  — «Ошибка»   : круг 88, #FFE03A
  — «Позже»    : круг 88, белый
  — «Готово»   : пилюля 176 × 88, #66DF66
```
Штампы: «Готово» зелёный `rotate(-10deg)`, «Ошибка» жёлтый `rotate(10deg)`, 24/600, появляются по `opacity`.

### 8.12 Стикер (рукописная заметка)

```
width/height: ~215 (30–40 % карточки);
background: #CFE4FF;
border-radius: 1.5% 1.5% 2% 10%;
box-shadow: var(--shadow-sticker);
transform: rotate(±6…9deg);
```
Внутри: автор — 15,6/18,7 `rgba(0,0,0,.55)`; текст — **Caveat 500** 25,6/28,2.

### 8.13 Тост

```
height: 44–48; padding: 0 20; radius 999;
background: var(--c-green) (успех) / #FFF (нейтральный);
font: 400 17/20; box-shadow: 0 2px 12px rgba(0,0,0,.1);
animation: toast-in var(--t-toast) var(--ease-ios); автоскрытие 2,5–3 с; уход — 180 ms ease-in
```
Позиция: над нижней навигацией, по центру.

### 8.14 Карточка виджета (сводка)

```
border-radius: 28 (squircle); background: #FFF; padding: 16; gap: 6;
.title  : 17–19 · 600
.row    : 17/24, между текстом и значением — flex, значение серым
.big    : 42–48 · 500 (число)
```

---

## 9. Состояния

| Состояние | Как выглядит |
|---|---|
| default | как в спецификации |
| pressed | `transform: scale(.96)`, 120 ms; для строк — фон `rgba(0,0,0,.03)` |
| selected (чип) | внутреннее кольцо 2 px `--c-blue` (без изменения размеров) |
| active (вкладка) | заливка `--c-blue`, белая иконка |
| disabled | `opacity: .45`, без тени, `pointer-events: none` |
| loading | скелетон тех же размеров (`#E8E4DE` на кремовом, `#ECEEF3` на сером), без спиннера |
| empty | заголовок 17/600 + пояснение 15/20 серым + одно действие |
| error | тост + красный текст 15/20 под полем; не модалка |
| unavailable | `opacity: .45`, метка «нет в наличии» |
| stop-list | число `--c-red`, строка приглушена |

---

## 10. Адаптив

| Ширина | Поведение |
|---|---|
| ≤ 379 | заголовки меньше (clamp), метаданные могут уйти в две строки |
| 380–430 | базовая сетка (390) |
| 431–599 | та же сетка, `--gutter` 16, карточки «Фокус» шире |
| ≥ 600 | телефон-макет 520 px по центру, рамка `0 0 0 10px #0B0B0D` |
| ≥ 1024 | макет остаётся 520; касса не растягивается на всю ширину |
| ландшафт | высота < 640 → уменьшаем вертикальные паддинги, колода становится горизонтальной каруселью |

Высота — через `dvh`, а не `vh` (мобильные браузеры меняют высоту при скролле).

---

## 11. Доступность

- Все интерактивные элементы — `<button>` (не `div`), с `aria-label`, если внутри только иконка.
- Выбор опции — `aria-pressed`; группа — `role="group"`; способы оплаты — `radiogroup` + `role="radio"` + `aria-checked`.
- Скрытые карты колоды — `inert` + `aria-hidden`.
- Лист — `role="dialog"`, `aria-modal="true"`, фокус-трап, `Esc` закрывает.
- Счётчики — `role="status"` с текстом («Введено цифр: 1 из 4»).
- Фокус видимый: кольцо 2 px `--c-blue` со смещением 2 px.
- `prefers-reduced-motion` — все переходы в 0 (см. tokens.css), goo и полёт картинки не запускаются.
- Тач-таргет ≥ 44; текст ≥ 12 px; контраст ≥ 4.5:1.
- Тёмная схема не поддерживается (только light) — `color-scheme: light` обязателен.

---

## 12. Производительность

1. Анимировать только `transform`, `opacity`, `filter` — не `width/height/top/left`.
2. `will-change: transform` — точечно и снимать после анимации.
3. `backdrop-filter` — максимум на 2 элементах одновременно; на Android < 9 отключать через `@supports`.
4. SVG goo-фильтр — только на 620 ms, один слой, не в списках.
5. Картинки — PNG/WebP 320 px, `loading="lazy"`, `object-fit: contain`.
6. Шрифт — `preconnect`, `display: swap`, локальный фолбэк.
7. Списки — не более 30 узлов на строку; виртуализация при > 200 позициях.
8. Жесты — `touch-action: pan-y` на карточках, `overscroll-behavior: none` на контейнерах.
9. Никаких библиотек анимаций: Web Animations API достаточно.
10. Бюджет: 60 fps на iPhone SE 2 / Redmi 9; LCP < 2.5 с на 4G.

---

## 13. Готовые сниппеты

### 13.1 Кнопки

```css
.btn        { height: 64px; padding: 0 24px; border: 0; border-radius: var(--r-pill);
              font: 400 24px/1 var(--font-ui); display: inline-flex; align-items: center;
              justify-content: center; gap: 8px; cursor: pointer;
              transition: transform var(--t-tap) var(--ease-out); }
.btn:active { transform: scale(.96); }

.btn--primary { background: var(--c-blue); color: #fff; box-shadow: var(--shadow-btn); }
.btn--money   { background: var(--c-pink); color: #fff; font-size: 36px; letter-spacing: -1.44px; }
.btn--secondary { background: #fff; color: var(--c-fg-ink); box-shadow: 0 1px 2px rgba(0,0,0,.06); }
.btn--ghost   { background: transparent; color: var(--c-fg); }
.btn[disabled]{ opacity: .45; pointer-events: none; }

.icon-btn   { width: 64px; height: 64px; border: 2px solid var(--c-line); border-radius: 50%;
              background: #fff; box-shadow: var(--shadow-btn);
              display: grid; place-items: center; }
.icon-btn:active { transform: scale(.94); }

/* Кнопка «В корзину» / «Оплатить» */
.price-btn { flex: 1; height: 64px; border: 0; border-radius: var(--r-pill);
             background: var(--c-blue); color: #fff;
             display: flex; align-items: center; justify-content: center; gap: 8px;
             transition: transform var(--t-tap) var(--ease-out), filter var(--t-tap); }
.price-btn__total { font-size: 36px; line-height: 44px; font-weight: 400; letter-spacing: -.04em; }
.price-btn:active { transform: scale(.98); filter: brightness(.95); }
.price-btn[disabled] { background: #E7E9F0; color: var(--c-fg-tertiary); pointer-events: none; }
```

### 13.2 Чип

```css
.chip {
  height: 60px; padding: 8px 16px; margin: 2px; border: 2px solid var(--c-line); border-radius: var(--r-pill);
  background: #fff; color: var(--c-fg);
  font: 400 19px/24px var(--font-ui);
  display: inline-flex; align-items: center; gap: 6px;
  transition: border-color var(--t-chip) var(--ease-out), box-shadow var(--t-chip) var(--ease-out),
              transform var(--t-tap) var(--ease-out), background 140ms;
}
.chip.is-selected {                       /* толщина рамки та же → ничего не reflow */
  border-color: var(--c-blue-ring);
  box-shadow: inset 0 0 0 1px var(--c-blue-ring);
}
.chip:active { transform: scale(.96); }
.chip small { font-size: 14px; line-height: 16px; }
```

### 13.3 Строка списка

```css
.list { display: flex; flex-direction: column; }

.row {
  display: grid; grid-template-columns: 72px 1fr auto; column-gap: 12px;
  align-items: center; width: 100%; padding: 10px 2px;
  border: 0; background: none; text-align: left; border-radius: 16px;
  cursor: pointer; position: relative;
}
.row + .row::before {
  content: ""; position: absolute; top: 0; left: 64px; right: 4px;
  height: 1px; background: var(--c-line-strong);
}
.row:active { background: rgba(0, 0, 0, .03); }
.row.is-unavailable { opacity: .45; }

.row__img  { width: 72px; height: 72px; display: grid; place-items: center; }
.row__img img { width: 68px; height: 68px; object-fit: contain; }
.row__name { font: 400 19px/24px var(--font-ui); }
.row__price{ font: 500 19px/24px var(--font-ui); justify-self: end; }
.row__meta { font: 400 17px/20px var(--font-ui); letter-spacing: -.255px; color: var(--c-fg-tertiary); }
```

### 13.4 Лист

```css
.sheet {
  position: fixed; left: 8px; right: 8px; bottom: 0;
  background: var(--c-paper);
  border-radius: calc(var(--r-outer) * var(--sq-k)) calc(var(--r-outer) * var(--sq-k)) 0 0;
  display: flex; flex-direction: column; max-height: 92dvh;
  animation: slide-up var(--t-sheet) var(--ease-ios);
  box-shadow: var(--shadow-sheet);
}
.sheet__head {
  display: flex; align-items: center;
  height: 76px; padding: 0;
}
.sheet__head img { width: 64px; height: 64px; margin: 4px; object-fit: contain; }
.sheet__title { font: 500 28px/32px var(--font-ui); padding: 8px 12px; }
.sheet__body { padding: 4px 10px 12px; overflow: auto; }
.sheet__foot { display: flex; align-items: center; gap: 6px; padding: 8px; height: 80px; }
```

### 13.5 Тост

```css
.toast {
  position: fixed; left: 50%; transform: translateX(-50%);
  bottom: calc(96px + var(--safe-bottom));
  background: #fff; border-radius: var(--r-pill);
  padding: 12px 20px; font: 400 17px/20px var(--font-ui);
  box-shadow: var(--shadow-btn);
  animation: toast-in var(--t-toast) var(--ease-ios);
}
.toast--ok { background: var(--c-green); }
```

---

## 14. Do / Don't

| Do | Don't |
|---|---|
| Радиусы концентричны (внутренний = внешний − отступ) | Ставить 16 внутри 40 без пересчёта |
| Один акцент на экран | Синяя кнопка + розовая сумма + зелёный бейдж одновременно |
| Крупные формы и большой воздух | Плотные таблицы, 1 px сетки на всё |
| Вес 500 для акцентов | Всё жирным 700 |
| Числа табличные | Пропорциональные цифры в таймере/сумме |
| Движение 120–400 ms, одна кривая | Пружины на 800 ms, разные кривые в одном переходе |
| Смысловые отступы 4/6/8/12/16 | Случайные 7/13/21 |
| Границы кольцами 1–2 px | Рамки 1 px `#ccc` |
| Пустое состояние с действием | Пустой экран с «Нет данных» |
| Один PR = один экран + скриншот до/после | Массовый рефакторинг вместе с редизайном |

---

## 15. Чек-лист приёмки (для экрана бариста)

**Вёрстка**
- [ ] Желоб 13,26 px на 390 / 16 px на 520; контент не липнет к краям.
- [ ] Верхний отступ учитывает `safe-top`, нижний — `safe-bottom`.
- [ ] Все радиусы кратны токенам; вложенные концентричны.
- [ ] Ни одной «случайной» 1 px рамки, только кольца/`--c-line`.

**Типографика**
- [ ] Размеры только из шкалы; нет 15,5 px и 18 px «на глаз».
- [ ] Числа — `tabular-nums`.
- [ ] Крупные заголовки `text-wrap: balance`, трекинг −0.02em.
- [ ] Длинные названия обрезаются `…`, а не ломают сетку.

**Цвет**
- [ ] Один акцент на экран.
- [ ] Контраст текста ≥ 4.5:1.
- [ ] Статусы дублируются словом, не только цветом.

**Движение**
- [ ] Нажатия 120 ms, переходы экранов 320–540 ms, `ease-ios`.
- [ ] `prefers-reduced-motion` полностью выключает анимации.
- [ ] Нет анимации `width/height/top/left`.

**Поведение**
- [ ] Тач-таргеты ≥ 44 px.
- [ ] Все кнопки — `<button>` с `aria-label`/`aria-pressed`.
- [ ] Лист закрывается `Esc`, свайпом и по фону.
- [ ] Пустые/ошибочные/загрузочные состояния есть у каждого блока.
- [ ] Данные приходят из существующих API — новых источников нет.

**Регресс**
- [ ] `docs/BARISTA-FLOW-V2.md`, пункты 1–11 — пройдены.
- [ ] Корзина → «В чек» → возврат из Эвотора: поведение как до редизайна.
- [ ] Добавки без `evotorUuid` и `available=false` по-прежнему блокируются.
- [ ] `extras.sc` и `<APP_UUID>.sc` в чеке не изменились.
- [ ] Очередь `PENDING/CONFIRMED/READY` и TTL 10 мин не затронуты.

---

## 16. Что делать первым

1. Вставить `tokens.css` (§2) — приложение должно выглядеть почти так же, но с новыми переменными.
2. Перевести один экран (например, каталог) на `row` + `section-title` + нижнюю навигацию.
3. Показать скриншоты до/после на 390 и 520.
4. Только после приёмки — лист товара, затем очередь/колода, затем корзина.
5. Эффекты (goo, одометр, полёт картинки) — в самом конце, отдельными PR.

Всё, что не описано в этом документе, — **не меняем**. Если для задачи не хватает правила, добавляем его сюда, а не «придумываем на месте».
