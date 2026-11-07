package ru.sixthcup.evotor.data

import java.math.BigDecimal

/**
 * Конфигурация товара от backend (`GET /api/devices/products/{id}/configuration`).
 *
 * Три независимые группы: объёмы, молоко, добавки. Молоко — отдельная сущность, не часть добавок.
 * UUID здесь — ссылки на номенклатуру Эвотор; цену и наименование касса берёт из неё, а не из ответа.
 */
data class ProductConfig(
    val schemaVersion: Int,
    val storeUuid: String,
    val productId: Int,
    val volumes: List<VolumeOption> = emptyList(),
    /** `null` — ни одному объёму схема молока не назначена. */
    val milk: MilkConfig? = null,
    /** `null` — ни одному объёму схема добавок не назначена. */
    val additions: AdditionsConfig? = null,
    /** `null` — ни одному объёму схема обязательного выбора (корица) не назначена. */
    val choice: ChoiceConfig? = null,
    /**
     * Коды проблем от backend (`configErrors`): например `links` — объёмы не привязаны
     * к товарам Эвотор этой точки. Показываем кассиру, чтобы причина была видна без логов.
     */
    val configErrors: List<String> = emptyList(),
) {
    /** Есть ли что выбирать кассиру. Пустая конфигурация = обычное добавление товара. */
    val hasChoices: Boolean
        get() = volumes.size > 1 || milk != null || additions != null || choice != null

    fun milkFor(productId: Int): MilkScheme? = milk?.byProductId?.get(productId)

    fun additionsFor(productId: Int): AdditionScheme? = additions?.byProductId?.get(productId)

    fun choiceFor(productId: Int): ChoiceScheme? = choice?.byProductId?.get(productId)

    /**
     * Схема молока строго для объёма с этим Evotor UUID.
     * Неизвестный объём — `null`: подставлять схему другого размера нельзя (аудит Q4).
     */
    fun milkForVolume(evotorUuid: String?): MilkScheme? {
        val pid = volumes.firstOrNull {
            it.evotorUuid != null && it.evotorUuid.equals(evotorUuid, ignoreCase = true)
        }?.productId ?: return null
        val scheme = milkFor(pid) ?: return null
        return scheme.takeIf { it.options.isNotEmpty() }
    }

    fun additionsForVolume(evotorUuid: String?): AdditionScheme? {
        val pid = volumes.firstOrNull {
            it.evotorUuid != null && it.evotorUuid.equals(evotorUuid, ignoreCase = true)
        }?.productId ?: return null
        val scheme = additionsFor(pid) ?: return null
        return scheme.takeIf { it.options.isNotEmpty() }
    }
}

data class VolumeOption(
    val productId: Int,
    val evotorUuid: String?,
    val name: String,
    val label: String,
    val sizeValue: Int?,
    val sizeUnit: String?,
    val priceRub: BigDecimal?,
    val available: Boolean,
    /** Почему объём не продаётся (`no_store_link` / `no_evotor_uuid` / `zero_price`); `null` — готов. */
    val notReadyReason: String? = null,
)

data class MilkConfig(
    /** Схема обязательна: без выбора продавать нельзя. */
    val required: Boolean,
    val byProductId: Map<Int, MilkScheme>,
)

data class MilkScheme(val schemeId: Int?, val options: List<MilkOption>)

data class MilkOption(
    val id: Int,
    val productId: Int?,
    val label: String,
    val evotorUuid: String?,
    /** Обычное молоко: показываем, в чек не отправляем, цену не добавляем. */
    val isFree: Boolean,
    val isDefault: Boolean,
    val sendToReceipt: Boolean,
    val available: Boolean,
    val notReadyReason: String? = null,
) {
    /** Идёт ли отдельной позицией чека. */
    val receiptLine: Boolean
        get() = available && !isFree && sendToReceipt && !evotorUuid.isNullOrBlank()
}

data class AdditionsConfig(
    val required: Boolean,
    val byProductId: Map<Int, AdditionScheme>,
)

data class AdditionScheme(val schemeId: Int?, val options: List<AdditionOption>)

data class AdditionOption(
    val id: Int,
    val productId: Int?,
    val name: String,
    val groupKey: String,
    val evotorUuid: String?,
    val priceRub: BigDecimal,
    val required: Boolean,
    val maxCount: Int,
    val isDefault: Boolean,
    val zeroOption: Boolean,
    val available: Boolean,
    val sendToReceipt: Boolean,
    val notReadyReason: String? = null,
) {
    val receiptLine: Boolean
        get() = available && !zeroOption && sendToReceipt && !evotorUuid.isNullOrBlank()
}

/**
 * Обязательный выбор без цены («с корицей / без корицы»).
 *
 * Не товар Эвотор и не добавка: в чек отдельной позицией не идёт, сумму не меняет.
 * Выбор запоминается в строке корзины и печатается на чеке через PrintExtra.
 */
data class ChoiceConfig(
    val required: Boolean,
    val byProductId: Map<Int, ChoiceScheme>,
)

/**
 * [schemeId] `null` — у этого объёма схемы нет (блока нет, продажу не ограничиваем).
 * [invalid] — сервер пометил схему повреждённой (`invalid_scheme`): продавать нельзя.
 */
data class ChoiceScheme(
    val schemeId: Int?,
    val title: String,
    val options: List<ChoiceOption>,
    val invalid: Boolean = false,
) {
    /** Есть ли что показывать кассиру. */
    val active: Boolean get() = schemeId != null
}

data class ChoiceOption(
    val id: Int,
    val label: String,
    val isDefault: Boolean,
)
