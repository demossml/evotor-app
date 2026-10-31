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
) {
    /** Есть ли что выбирать кассиру. Пустая конфигурация = обычное добавление товара. */
    val hasChoices: Boolean
        get() = volumes.size > 1 || milk != null || additions != null

    fun milkFor(productId: Int): MilkScheme? = milk?.byProductId?.get(productId)

    fun additionsFor(productId: Int): AdditionScheme? = additions?.byProductId?.get(productId)
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
) {
    val receiptLine: Boolean
        get() = available && !zeroOption && sendToReceipt && !evotorUuid.isNullOrBlank()
}
