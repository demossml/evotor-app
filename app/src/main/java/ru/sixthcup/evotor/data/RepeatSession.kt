package ru.sixthcup.evotor.data

/**
 * Очередь кнопки «Повторить»: одна позиция за раз, без «перескока» на следующую,
 * если текущая не добавлена.
 *
 * Правила:
 * 1. [start] перезапускает очередь целиком — старая не смешивается с новой;
 * 2. следующая позиция открывается только после фактического добавления ([added]);
 *    ошибка конфигурации или закрытое окно очередь не продвигают;
 * 3. ручное добавление другого товара очередь не трогает;
 * 4. закрытие окна без выбора помечает повтор незавершённым ([incomplete]), остаток
 *    сохраняется и может быть отменён явно ([cancel]).
 */
class RepeatSession {

    private val queue = ArrayDeque<CatalogProduct>()

    /** Позиция, окно которой открыто сейчас. */
    var pending: CatalogProduct? = null
        private set

    /** Повтор прерван: кассир закрыл окно, не добавив позицию. */
    var incomplete: Boolean = false
        private set

    var completed: Int = 0
        private set

    var remaining: Int = queue.size
        private set

    /** Очередь ещё не доиграна (есть открытая или ожидающие позиции). */
    val active: Boolean get() = pending != null || queue.isNotEmpty()

    /** Новая очередь. Возвращает первую позицию (её окно и надо открыть) или `null`. */
    fun start(items: List<CatalogProduct>): CatalogProduct? {
        queue.clear()
        pending = null
        incomplete = false
        completed = 0
        queue.addAll(items)
        return next()
    }

    fun isPending(product: CatalogProduct): Boolean = pending?.uuid == product.uuid

    /**
     * Позиция фактически добавлена в корзину. Возвращает следующую или `null`,
     * если очередь закончилась либо добавлен «не тот» товар (ручное добавление).
     */
    fun added(product: CatalogProduct): CatalogProduct? {
        if (!isPending(product)) return null
        completed++
        return next()
    }

    /**
     * Окно закрыто без добавления. `true` — это была позиция повтора: она не добавлена,
     * остаток сохранён, повтор помечен незавершённым.
     */
    fun closedWithoutAdd(product: CatalogProduct): Boolean {
        if (!isPending(product)) return false
        pending = null
        incomplete = true
        return true
    }

    /** Продолжить прерванный повтор: следующая позиция или `null`. */
    fun resume(): CatalogProduct? = next()

    /** Отменить остаток. Возвращает число не добавленных позиций. */
    fun cancel(): Int {
        val lost = queue.size + (if (pending != null) 1 else 0)
        queue.clear()
        pending = null
        incomplete = false
        remaining = 0
        return lost
    }

    private fun next(): CatalogProduct? {
        pending = queue.removeFirstOrNull()
        remaining = queue.size
        if (pending == null && incomplete) incomplete = false
        return pending
    }
}
