package ru.sixthcup.evotor.data

/**
 * Проверки конфигурации перед отправкой позиции в чек.
 *
 * Правило: если конфигурация товара известна, продавать его можно только при закрытых
 * обязательных схемах ИМЕННО этого объёма. Схему соседнего размера или UUID основного
 * товара подставлять нельзя — иначе обязательное молоко обходится (аудит Q1/Q4).
 */
object ProductConfigGuard {

    /** `productId` объёма, к которому относится товар терминала. `null` — объём не опознан. */
    fun volumeProductId(cfg: ProductConfig, volumeEvotorUuid: String?): Int? = when {
        cfg.volumes.isNotEmpty() ->
            cfg.volumes.firstOrNull { !it.evotorUuid.isNullOrBlank() && it.evotorUuid == volumeEvotorUuid }?.productId
        else -> cfg.productId.takeIf { it > 0 }
    }

    fun milkScheme(cfg: ProductConfig, volumeEvotorUuid: String?): MilkScheme? =
        volumeProductId(cfg, volumeEvotorUuid)?.let { cfg.milk?.byProductId?.get(it) }

    fun additionScheme(cfg: ProductConfig, volumeEvotorUuid: String?): AdditionScheme? =
        volumeProductId(cfg, volumeEvotorUuid)?.let { cfg.additions?.byProductId?.get(it) }

    /**
     * Причины, по которым позицию нельзя отдать в чек при известной конфигурации.
     * Пустой список — ограничений нет (обязательные схемы либо не заданы, либо укомплектованы).
     */
    fun blockers(cfg: ProductConfig?, volumeEvotorUuid: String?): List<String> {
        val c = cfg ?: return emptyList()
        val pid = volumeProductId(c, volumeEvotorUuid)
        if (c.volumes.isNotEmpty() && pid == null) {
            return listOf("Объём не опознан: выберите другой размер")
        }
        val out = ArrayList<String>(2)
        val milk = c.milk
        if (milk != null && milk.required) {
            val scheme = pid?.let { milk.byProductId[it] }
            when {
                scheme == null || scheme.options.isEmpty() ->
                    out.add("Нет схемы молока для объёма — продажа запрещена")
                scheme.options.none { it.available } -> out.add("Молоко недоступно — продажа запрещена")
            }
        }
        val additions = c.additions
        if (additions != null) {
            val scheme = pid?.let { additions.byProductId[it] }
            when {
                (scheme == null || scheme.options.isEmpty()) && additions.required ->
                    out.add("Нет схемы добавок для объёма — продажа запрещена")
                scheme != null && scheme.options.any { it.required } && scheme.options.none { it.available } ->
                    out.add("Обязательные добавки недоступны — продажа запрещена")
            }
        }
        return out
    }
}
