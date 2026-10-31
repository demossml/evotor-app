package ru.sixthcup.evotor.ui

import ru.sixthcup.evotor.data.ProductConfig
import ru.sixthcup.evotor.data.ProductConfigGuard
import ru.sixthcup.evotor.data.ToppingRules
import java.math.BigDecimal

/**
 * Серверная конфигурация → опции окна товара.
 *
 * Цена берётся из номенклатуры Эвотор (`priceOf`), а не из ответа конфигурации: иначе чек
 * разойдётся с товаром и склад модификаторов не спишется. Платное молоко/добавка без товара
 * Эвотор недоступны — их нельзя провести фискально.
 *
 * `required` у молока — свойство схемы (обязательна группа), у добавок — свойство опции.
 */
object ConfigOptsMapper {

    fun milk(
        cfg: ProductConfig,
        volumeEvotorUuid: String?,
        priceOf: (String) -> BigDecimal? = { null },
    ): List<OptionPicker.Opt> {
        val scheme = ProductConfigGuard.milkScheme(cfg, volumeEvotorUuid) ?: return emptyList()
        val required = cfg.milk?.required == true
        return scheme.options.map { o ->
            val onTerminal = o.evotorUuid?.let { priceOf(it) != null } ?: false
            OptionPicker.Opt(
                key = "c:m:${o.id}",
                label = o.label,
                price = if (o.isFree) BigDecimal.ZERO else priceOf(o.evotorUuid.orEmpty()) ?: BigDecimal.ZERO,
                evotorUuid = o.evotorUuid,
                isDefault = o.isDefault,
                required = required,
                zeroOption = o.isFree,
                available = o.available && (o.isFree || onTerminal),
                maxCount = 1,
                id = o.id,
                sendToReceipt = o.sendToReceipt,
            )
        }
    }

    fun additions(
        cfg: ProductConfig,
        volumeEvotorUuid: String?,
        priceOf: (String) -> BigDecimal? = { null },
    ): List<OptionPicker.Opt> {
        val scheme = ProductConfigGuard.additionScheme(cfg, volumeEvotorUuid) ?: return emptyList()
        return scheme.options.map { o ->
            val onTerminal = o.evotorUuid?.let { priceOf(it) != null } ?: false
            OptionPicker.Opt(
                key = "c:a:${o.id}",
                label = o.name,
                price = priceOf(o.evotorUuid.orEmpty()) ?: o.priceRub,
                evotorUuid = o.evotorUuid,
                isDefault = o.isDefault,
                required = o.required,
                zeroOption = o.zeroOption,
                available = o.available && (o.zeroOption || onTerminal),
                maxCount = o.maxCount,
                id = o.id,
                sendToReceipt = o.sendToReceipt,
            )
        }
    }

    /** Все группы окна или `null`, если схем для объёма нет (работаем по каталогу). */
    fun all(
        cfg: ProductConfig,
        volumeEvotorUuid: String?,
        priceOf: (String) -> BigDecimal? = { null },
    ): OptionPicker.Opts? {
        val milk = milk(cfg, volumeEvotorUuid, priceOf)
        val additions = additions(cfg, volumeEvotorUuid, priceOf)
        if (milk.isEmpty() && additions.isEmpty()) return null
        fun group(key: String) = additions.filter { it.key.startsWith("c:a:") && keyOf(cfg, it.key) == key }
        return OptionPicker.Opts(
            milk = milk,
            syrup = group("syrup"),
            sugar = group("sugar"),
            extra = additions.filterNot {
                keyOf(cfg, it.key) in setOf("syrup", "sugar", "milk")
            },
        )
    }

    /** `groupKey` опции добавок по её UI-ключу. */
    private fun keyOf(cfg: ProductConfig, key: String): String? {
        val id = key.removePrefix("c:a:").toIntOrNull() ?: return null
        return cfg.additions?.byProductId?.values?.asSequence()
            ?.flatMap { it.options.asSequence() }
            ?.firstOrNull { it.id == id }?.groupKey?.lowercase()
    }

    /** Группы правил для [ToppingRules.blockers] — ровно то, что видит кассир в окне. */
    fun ruleGroups(opts: OptionPicker.Opts): List<Pair<String, List<ru.sixthcup.evotor.data.OptionRule>>> =
        listOf("Молоко" to opts.milk, "Сироп" to opts.syrup, "Сахар" to opts.sugar, "Добавки" to opts.extra)
            .map { (title, list) -> title to list.map { it.rule() } }
}
