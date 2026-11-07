package ru.sixthcup.evotor.ui

/**
 * Какие блоки листа товара будут нарисованы.
 *
 * Единственный источник условий: и `OptionPicker.render()`, и тесты спрашивают одно и то же.
 * Пустой набор опций = только «Объём» — именно так выглядел баг «UI opts есть, а секций нет».
 */
object SheetSections {

    const val VOLUME = "Объём"
    const val MILK = "Молоко"
    const val SYRUP = "Сироп"
    const val SUGAR = "Сахар"
    const val EXTRA = "Добавки"
    /** Заголовок блока выбора берётся из схемы (`title`), здесь только признак секции. */
    const val CHOICE = "Выбор"

    fun headers(
        variantCount: Int,
        milkCount: Int,
        syrupCount: Int,
        sugarCount: Int,
        extraCount: Int,
        choiceCount: Int = 0,
    ): List<String> {
        val out = ArrayList<String>(5)
        if (variantCount > 1) out.add(VOLUME)
        if (milkCount > 0) out.add(MILK)
        // Выбор без цены — между молоком и сиропом/добавками.
        if (choiceCount > 0) out.add(CHOICE)
        if (syrupCount > 0) out.add(SYRUP)
        if (sugarCount > 0) out.add(SUGAR)
        if (extraCount > 0) out.add(EXTRA)
        return out
    }
}
