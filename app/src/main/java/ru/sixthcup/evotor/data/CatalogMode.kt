package ru.sixthcup.evotor.data

/**
 * Как строить витрину каталога.
 *
 * `features.serverCatalog` управляется backend'ом по версии кассы. На 5.2.22 выяснилось,
 * что в серверном режиме витрина рассыпается: каждый объём становится отдельной плиткой,
 * а в карточке пропадает блок «Объём» (плитка = один товар), схемы добавок тоже не приходят.
 * Поэтому серверный режим выключен до проверки каталога на кассе: касса шлёт заголовок версии
 * (backend видит сборку), но витрину строит прежней склейкой по меткам/группам.
 */
object CatalogMode {

    /** Разрешить серверную витрину. Включать только после проверки `features.serverCatalog` на кассе. */
    const val SERVER_CATALOG_ALLOWED = false

    fun useServerCatalog(featureFlag: Boolean?, allowed: Boolean = SERVER_CATALOG_ALLOWED): Boolean =
        allowed && featureFlag == true
}
