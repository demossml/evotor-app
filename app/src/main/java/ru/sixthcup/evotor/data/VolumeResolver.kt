package ru.sixthcup.evotor.data

/**
 * Какой объём конфигурации соответствует товару терминала.
 *
 * Один только `evotorUuid` ненадёжен: backend берёт его из `product_store_links`, и на точке
 * он может не совпасть с uuid товара в номенклатуре (не выгружен, пересоздан, другой регистр).
 * Тогда схемы молока/добавок терялись, и лист оставался с одними объёмами.
 *
 * Порядок сопоставления (строго по одному и тому же товару, без «доноров»):
 * 1. `evotorUuid` (без учёта регистра);
 * 2. `productId` объёма = id товара на сервере (из enrich каталога по uuid терминала);
 * 3. объём в мл/г (`sizeValue`/`sizeUnit`) = `#size=` товара;
 * 4. точное имя объёма = имя товара терминала.
 */
data class VolumeRef(
    val evotorUuid: String?,
    /** `productId` товара на сервере (тот же, что в пути `/products/{id}/configuration`). */
    val backendProductId: Int? = null,
    val sizeMl: Int? = null,
    val name: String? = null,
)

object VolumeResolver {

    enum class By { UUID, PRODUCT_ID, SIZE, NAME, NONE }

    data class Hit(val productId: Int, val by: By)

    fun resolve(cfg: ProductConfig?, ref: VolumeRef): Hit? {
        val volumes = cfg?.volumes.orEmpty().filter { it.productId > 0 }
        if (volumes.isEmpty()) return null

        val uuid = ref.evotorUuid?.trim().orEmpty()
        if (uuid.isNotEmpty()) {
            volumes.firstOrNull { !it.evotorUuid.isNullOrBlank() && it.evotorUuid.equals(uuid, ignoreCase = true) }
                ?.let { return Hit(it.productId, By.UUID) }
        }
        val pid = ref.backendProductId?.takeIf { it > 0 }
        if (pid != null) {
            volumes.firstOrNull { it.productId == pid }?.let { return Hit(it.productId, By.PRODUCT_ID) }
        }
        val size = ref.sizeMl?.takeIf { it > 0 }
        if (size != null) {
            volumes.firstOrNull { it.sizeValue == size }?.let { return Hit(it.productId, By.SIZE) }
            volumes.firstOrNull { it.label.contains(size.toString()) }?.let { return Hit(it.productId, By.SIZE) }
        }
        val name = ref.name?.trim().orEmpty()
        if (name.isNotEmpty()) {
            volumes.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return Hit(it.productId, By.NAME) }
        }
        return null
    }
}
