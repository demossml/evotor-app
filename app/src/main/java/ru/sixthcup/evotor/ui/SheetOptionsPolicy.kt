package ru.sixthcup.evotor.ui

/**
 * Нужно ли вообще собирать опции для листа товара.
 *
 * Раньше лист смотрел только на каталог (`#mods`, `toppings[]`, товары `#opt=`), и при полной
 * серверной конфигурации (молоко + добавки) опции не собирались вовсе: в окне оставались
 * только объёмы. Конфигурация — такой же источник опций, как каталог.
 */
object SheetOptionsPolicy {

    fun needed(
        configPresent: Boolean,
        supportsMods: Boolean,
        hasToppings: Boolean,
        hasEvotorOptions: Boolean,
    ): Boolean = configPresent || supportsMods || hasToppings || hasEvotorOptions
}
