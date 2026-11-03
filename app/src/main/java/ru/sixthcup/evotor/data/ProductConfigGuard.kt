package ru.sixthcup.evotor.data

/**
 * Проверки конфигурации перед отправкой позиции в чек.
 *
 * Правило: если конфигурация товара известна, продавать его можно только при закрытых
 * обязательных схемах ИМЕННО этого объёма. Схему соседнего размера или UUID основного
 * товара подставлять нельзя — иначе обязательное молоко обходится (аудит Q1/Q4).
 */
object ProductConfigGuard {

    /** `productId` объёма, к которому относится товар терминала (по uuid, id, размеру, имени). */
    fun volumeProductId(cfg: ProductConfig, ref: VolumeRef): Int? =
        VolumeResolver.resolve(cfg, ref)?.productId
            // Конфигурация без объёмов относится к товару целиком.
            ?: cfg.productId.takeIf { it > 0 && cfg.volumes.isEmpty() }

    /** Совместимость: сопоставление только по uuid терминала. */
    fun volumeProductId(cfg: ProductConfig, volumeEvotorUuid: String?): Int? =
        volumeProductId(cfg, VolumeRef(evotorUuid = volumeEvotorUuid))

    /** Совместимость: сопоставление только по uuid терминала. */
    fun blockers(cfg: ProductConfig?, volumeEvotorUuid: String?): List<String> =
        blockers(cfg, VolumeRef(evotorUuid = volumeEvotorUuid))

    fun milkScheme(cfg: ProductConfig, ref: VolumeRef): MilkScheme? =
        volumeProductId(cfg, ref)?.let { cfg.milk?.byProductId?.get(it) }

    fun milkScheme(cfg: ProductConfig, volumeEvotorUuid: String?): MilkScheme? =
        milkScheme(cfg, VolumeRef(evotorUuid = volumeEvotorUuid))

    fun additionScheme(cfg: ProductConfig, ref: VolumeRef): AdditionScheme? =
        volumeProductId(cfg, ref)?.let { cfg.additions?.byProductId?.get(it) }

    fun additionScheme(cfg: ProductConfig, volumeEvotorUuid: String?): AdditionScheme? =
        additionScheme(cfg, VolumeRef(evotorUuid = volumeEvotorUuid))

    /**
     * Относится ли конфигурация к этой карточке.
     *
     * Вариант 1 — объём карточки найден среди `volumes` (обычный случай).
     * Вариант 2 — конфигурация запрошена именно для этого товара (`productId` совпал),
     * но backend не связал объёмы с `evotorUuid`. Тогда схемы не применяем, однако продавать
     * вслепую нельзя: [blockers] скажет «Объём не опознан», если схемы вообще есть.
     * Конфигурация без схем (только объёмы) продажу не ограничивает.
     */
    fun appliesTo(cfg: ProductConfig, cardEvotorUuids: Set<String>, cardProductId: Int?): Boolean {
        // Только uuid: если по нему объём не нашёлся, решает совпадение productId ниже.
        val volumeHit = cfg.volumes.any { v ->
            !v.evotorUuid.isNullOrBlank() && cardEvotorUuids.any { it.equals(v.evotorUuid, ignoreCase = true) }
        }
        if (volumeHit) return true
        if (cfg.milk == null && cfg.additions == null) return false
        return cardProductId != null && cardProductId > 0 && cardProductId == cfg.productId
    }

    /**
     * Причины, по которым позицию нельзя отдать в чек при известной конфигурации.
     * Пустой список — ограничений нет (обязательные схемы либо не заданы, либо укомплектованы).
     */
    fun blockers(cfg: ProductConfig?, ref: VolumeRef): List<String> {
        val c = cfg ?: return emptyList()
        val pid = volumeProductId(c, ref)
        if (c.volumes.isNotEmpty() && pid == null) {
            return listOf(
                if ("links" in c.configErrors) "Объёмы не привязаны к Эвотору на этой точке — продажа запрещена"
                else "Объём не опознан: выберите другой размер",
            )
        }
        // Объём помечен недоступным (нет связи с точкой / снят с продажи в админке).
        c.volumes.firstOrNull { it.productId == pid }
            ?.takeIf { !it.available }
            ?.let { return listOf(ConfigDiagnostics.unavailableText(it.notReadyReason)) }
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
