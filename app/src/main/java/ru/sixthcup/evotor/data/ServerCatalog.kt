package ru.sixthcup.evotor.data

import ru.sixthcup.evotor.net.CatalogApi

/**
 * Витрина в режиме `features.serverCatalog = true`.
 *
 * Правила (CHANGES-FROM-PLAN F2–F4):
 * 1. Плитки — только строки серверного каталога, которые **можно продать** (`sellable`) и не скрыты;
 *    цена и наличие берутся с терминала (номенклатура), потому что фискалит Эвотор.
 * 2. Нет uuid на терминале → товар не показываем и считаем [ServerShelf.missingOnTerminal].
 *    Пустой отклик push не должен давать «пустую кассу» молча — счётчик уходит в диагностику.
 * 3. Добавки листа — **только** из объекта этого же продукта в `products[]`.
 *    Донора по имени/соседнему размеру в этом режиме нет (F3): у `groups[].variants[]`
 *    toppings часто пустые, и склейка по имени даёт чужие схемы.
 * 4. Сопоставление с терминалом — только по identity: uuid → артикул `sc-…` → точное имя с объёмом.
 *    Volume-stripped имя («капучино» на три размера) в этом режиме не используется.
 */
object ServerCatalog {

    data class Shelf(
        val products: List<CatalogProduct>,
        /** Сколько строк серверного каталога продаваемы и показаны. */
        val shown: Int,
        /** Продаваемых, но скрытых флагом (hideFromMenu / группа / модификатор). */
        val hiddenByFlag: Int,
        /** `sellable = false` — в витрину не идут (остаются в JSON для админки и диагностики). */
        val notSellable: Int,
        /** Продаваемые с сервера, но номенклатуры ещё нет на терминале. */
        val missingOnTerminal: Int,
        /** Товары терминала, которых нет в серверном каталоге (в server-режиме не показываем). */
        val absentOnServer: Int,
    ) {
        val total: Int get() = shown + hiddenByFlag + notSellable
    }

    fun build(terminal: List<CatalogProduct>, payload: CatalogApi.Payload): Shelf {
        // 1. Один раз считаем, что сервер разрешил показывать.
        val serverByUuid = HashMap<String, CatalogApi.Enrich>()
        val serverByArticle = HashMap<String, CatalogApi.Enrich>()
        val serverByExactName = HashMap<String, CatalogApi.Enrich>()
        for ((uuid, e) in payload.byUuid) {
            if (uuid.isNotEmpty()) serverByUuid[uuid] = e
        }
        for ((article, e) in payload.byArticle) {
            if (article.isNotEmpty()) serverByArticle[article.lowercase()] = e
        }
        for ((name, e) in payload.byNameExact) {
            if (name.isNotEmpty()) serverByExactName[name] = e
        }

        var hiddenByFlag = 0
        var notSellable = 0
        // Продаваемые строки сервера, которые должны попасть на витрину.
        val allowedUuids = HashSet<String>()
        for ((uuid, e) in serverByUuid) {
            when {
                !e.sellable -> notSellable++
                e.hideFromMenu || e.isGroup -> hiddenByFlag++
                else -> allowedUuids.add(uuid)
            }
        }

        val terminalUuids = HashSet<String>()
        var absentOnServer = 0
        val out = ArrayList<CatalogProduct>(allowedUuids.size)
        for (p in terminal) {
            terminalUuids.add(p.uuid)
            val e = identity(p, serverByUuid, serverByArticle, serverByExactName)
            if (e == null) {
                absentOnServer++
                continue
            }
            if (!e.sellable || e.hideFromMenu || e.isGroup) continue
            out.add(applyEnrich(p, e))
        }
        val missingOnTerminal = allowedUuids.count { it !in terminalUuids }

        return Shelf(
            products = out,
            shown = out.size,
            hiddenByFlag = hiddenByFlag,
            notSellable = notSellable,
            missingOnTerminal = missingOnTerminal,
            absentOnServer = absentOnServer,
        )
    }

    /** Только identity-совпадения: uuid, артикул `sc-…`, точное имя с объёмом. */
    private fun identity(
        p: CatalogProduct,
        byUuid: Map<String, CatalogApi.Enrich>,
        byArticle: Map<String, CatalogApi.Enrich>,
        byExactName: Map<String, CatalogApi.Enrich>,
    ): CatalogApi.Enrich? {
        byUuid[p.uuid]?.let { return it }
        p.article?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { key -> byArticle[key]?.let { return it } }
        return byExactName[Names.normalizeExact(p.name)]
    }

    /** Переносит серверные поля на товар терминала. Добавки — строго свои (без донора). */
    private fun applyEnrich(p: CatalogProduct, e: CatalogApi.Enrich): CatalogProduct = p.copy(
        // Товар есть в серверном каталоге — значит он «настоящий» пункт меню, а не служебная опция.
        hideFromMenu = p.hideFromMenu || e.hideFromMenu || e.isGroup,
        recipe = e.recipe ?: p.recipe,
        imageUrl = e.imageUrl ?: p.imageUrl,
        toppings = e.toppings,
        upsell = e.upsell,
        countsAsCupServer = e.countsAsCup ?: p.countsAsCupServer,
        freeEligible = e.freeEligible ?: p.freeEligible,
        featured = e.featured || p.featured,
        description = e.description ?: p.description,
        isFood = e.isFood ?: p.isFood,
        groupId = e.groupId ?: p.groupId,
        sizeMl = e.sizeValue ?: p.sizeMl,
        categoryName = e.categoryName ?: p.categoryName,
        // Состав листа (молоко/сироп) в server-режиме приходит только с продукта.
        supportsMods = p.supportsMods || e.toppings.isNotEmpty(),
    )
}
