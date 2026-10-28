package ru.sixthcup.evotor.net

import org.json.JSONArray
import org.json.JSONObject
import ru.sixthcup.evotor.BuildConfig
import ru.sixthcup.evotor.data.CatalogProduct
import ru.sixthcup.evotor.data.FoodRules
import ru.sixthcup.evotor.data.Names
import ru.sixthcup.evotor.data.ProductMarkers
import ru.sixthcup.evotor.data.Topping
import ru.sixthcup.evotor.data.ToppingJson
import ru.sixthcup.evotor.data.UpsellItem
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log

/**
 * Каталог 6.7 (топинги, фото, upsell, категории, фокус) через HTTPS/прокси Эвотор.
 * Секретов в APK нет. Вызывать с фона.
 *
 * Категории и «Фокус» берутся с backend, если в номенклатуре Эвотор нет parent-групп
 * или меток #focus в DESCRIPTION (типичный случай после push из админки).
 */
object CatalogApi {
    data class Payload(
        val byUuid: Map<String, Enrich>,
        val upsellDefault: List<UpsellItem>,
        /** Имена категорий из 6.7 (для чипов, если Evotor groups пустые). */
        val categoryNames: List<String> = emptyList(),
        /** Fallback for legacy/store UUID mismatches: only unique normalized product names are used. */
        val byName: Map<String, Enrich> = emptyMap(),
        /**
         * Точный fallback по полному имени с объёмом (`Names.normalizeExact`):
         * «Капучино 350 мл» → «капучино 350 мл». Закрывает случай, когда uuid разошёлся,
         * а volume-stripped `byName` даёт коллизию «капучино» для всех размеров.
         */
        val byNameExact: Map<String, Enrich> = emptyMap(),
        /**
         * Group-донор toppings: groupId → toppings (первый enrich в группе с непустыми
         * toppings). Используется, когда uuid и имя размера разошлись, но groupId общий.
         */
        val byGroupId: Map<Int, List<Topping>> = emptyMap(),
        /**
         * Разрешение неоднозначного volume-stripped имени к единственному groupId:
         * «капучино» (250/350/400) → groupId. Заполняется ТОЛЬКО если все продукты с этим
         * stripped-именем принадлежат одной группе (иначе — риск склеить чужое).
         */
        val nameGroupId: Map<String, Int> = emptyMap(),
        /** Store echoed by backend (for multi-store remember). */
        val storeUuid: String? = null,
        /**
         * Стабильная связка backend ↔ терминал: `articleNumber` (`sc-<id>`) ↔ `ProductTable.ROW_CODE`.
         * Приоритетнее имени: не зависит от переименований и UUID конкретной точки.
         */
        val byArticle: Map<String, Enrich> = emptyMap(),
        /** Категории-добавки с backend: скрываем по id категории, а не по слову в названии. */
        val addonCategoryIds: Set<Int> = emptySet(),
        /** `groups[]` из каталога: productId → groupId (fallback, если у товара не пришёл groupId). */
        val groupByProductId: Map<Int, Int> = emptyMap(),
        /** `groups[].variants[].evotorUuid` → groupId (fallback для сопоставления по uuid). */
        val groupByUuid: Map<String, Int> = emptyMap(),
        /**
         * Блок `menu` из контракта (`GET /api/devices/catalog`): порядок вкладок групп
         * и чип «Всё». `null` — backend старее настройки, касса работает как раньше.
         */
        val menu: Menu? = null,
    )

    /** `menu` каталога: как касса рисует чипы групп (админка → Меню → Группы). */
    data class Menu(
        /** Показывать чип «Всё». По умолчанию `true` — как было до настройки. */
        val showAllTab: Boolean = true,
        /** Имена групп в порядке показа (из `categories.sort_order`). */
        val order: List<String> = emptyList(),
        /**
         * Группы, скрытые на кассе: их товары остаются в `products[]`, но чип рисовать нельзя.
         * Нет поля (старый backend) — пусто, поведение прежнее.
         */
        val hidden: List<String> = emptyList(),
    )

    data class Enrich(
        val productId: Int,
        val name: String? = null,
        val imageUrl: String?,
        val recipe: String?,
        val countsAsCup: Boolean?,
        val freeEligible: Boolean?,
        val toppings: List<Topping>,
        val upsell: List<UpsellItem>,
        val categoryName: String? = null,
        val featured: Boolean = false,
        val description: String? = null,
        /** Явный флаг еды с backend (`isFood`), если пришёл. */
        val isFood: Boolean? = null,
        /** Backend product group and variant metadata from the 6.7 admin. */
        val groupId: Int? = null,
        val variantLabel: String? = null,
        val variantSort: Int? = null,
        val sizeValue: Int? = null,
        val sizeUnit: String? = null,
        val hideFromMenu: Boolean = false,
        /**
         * Backend products.is_group (миграция 027): папка Эвотора (Молоко/Сиропы/…),
         * не SKU. На витрине не показываем — как isModifier.
         */
        val isGroup: Boolean = false,
        /** Backend products.parent_product_id — локальный id группы-родителя (не Evotor uuid). */
        val parentProductId: Int? = null,
        /** `sc-<id>` из каталога (та же строка, что в `ProductTable.ROW_CODE`). */
        val articleNumber: String? = null,
        /** products.category_id: нужен для скрытия добавок по id категории. */
        val categoryId: Int? = null,
    )

    /** Результат запроса каталога: payload == null — сеть/сервер не отдали данные. */
    data class FetchResult(
        val payload: Payload?,
        val httpStatus: Int = 0,
        val error: String? = null,
        /** 200, но products=[] — такой ответ НЕ должен затирать кэш. */
        val emptyResponse: Boolean = false,
    )

    /** Последний успешный/неуспешный ответ: удобно для тестов и для UI-плашки. */
    fun fetch(storeUuid: String? = null): Payload =
        fetchDetailed(storeUuid).payload ?: Payload(emptyMap(), emptyList())

    /**
     * GET /api/devices/catalog с повторами: 5xx и сетевые ошибки повторяем с backoff 1с/3с,
     * всего не более 3 попыток. Таймауты контракта: connect ≤ 5с, read ≤ 10с.
     */
    fun fetchDetailed(storeUuid: String? = null): FetchResult {
        val backoff = longArrayOf(0L, 1000L, 3000L)
        var last = FetchResult(null, 0, "unknown")
        for (attempt in backoff.indices) {
            if (attempt > 0) {
                try { Thread.sleep(backoff[attempt]) } catch (_: InterruptedException) { return last }
            }
            last = attemptFetch(storeUuid)
            if (last.payload != null) return last
            // 4xx повторять бессмысленно (401/403/404 — вопрос конфигурации точки).
            if (last.httpStatus in 400..499) break
            if (attempt < backoff.lastIndex) {
                Log.w(
                    "CatalogApi",
                    "http_retry GET /api/devices/catalog attempt=${attempt + 2} code=${last.httpStatus}",
                )
            }
        }
        return last
    }

    private fun attemptFetch(storeUuid: String?): FetchResult {
        var conn: HttpURLConnection? = null
        return try {
            val q = if (!storeUuid.isNullOrBlank()) "?storeUuid=" + android.net.Uri.encode(storeUuid) else ""
            val url = BuildConfig.API_BASE_URL.trimEnd('/') + "/api/devices/catalog" + q
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
            }
            if (!storeUuid.isNullOrBlank()) conn.setRequestProperty("X-Evotor-Store-Uuid", storeUuid)
            DeviceHeaders.apply(conn) // device uuid; store already set above when known
            Log.i("CatalogApi", "catalog request store=${storeUuid ?: "-"}")
            // No Authorization in APK: Evotor Cloud injects it when installed from cabinet.
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                Log.w("CatalogApi", "catalog HTTP $status len=${body.length}")
                return FetchResult(null, status, "HTTP $status")
            }
            val root = JSONObject(body)
            val empty = (root.optJSONArray("products")?.length() ?: 0) == 0
            val parsed = parsePayload(body)
            val withPhoto = parsed.byUuid.values.count { !it.imageUrl.isNullOrBlank() }
            Log.i(
                "CatalogApi",
                "catalog ok byUuid=${parsed.byUuid.size} byName=${parsed.byName.size} " +
                    "withImageUrl=$withPhoto store=${storeUuid ?: "-"}",
            )
            FetchResult(parsed, status, null, empty)
        } catch (e: Throwable) {
            Log.e("CatalogApi", "catalog fetch failed", e)
            FetchResult(null, 0, e.message ?: e.javaClass.simpleName)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** Итоги сопоставления каталога с инвентаризацией — для диагностики (без секретов). */
    data class MergeStats(
        val total: Int = 0,
        val byUuid: Int = 0,
        val byArticle: Int = 0,
        val byExactName: Int = 0,
        val byName: Int = 0,
        val unmatched: Int = 0,
        /** Скрыто флагом identity-совпадения (hideFromMenu/isGroup). */
        val hiddenByFlag: Int = 0,
        /** Скрыто по id категории-добавки (без опоры на название товара). */
        val hiddenByCategory: Int = 0,
        /** Получили явный groupId (не по названию). */
        val groupedExplicit: Int = 0,
    )

    fun merge(products: List<CatalogProduct>, payload: Payload): List<CatalogProduct> =
        mergeWithStats(products, payload).first

    /**
     * Склейка серверного каталога с номенклатурой Эвотор.
     *
     * Идентичность (uuid → артикул `sc-<id>`) — единственный источник **скрытия** и **группировки**.
     * Имя остаётся ограниченным legacy-fallback: по нему можно дотянуть фото/добавки,
     * но нельзя скрыть товар или склеить разные позиции.
     */
    fun mergeWithStats(
        products: List<CatalogProduct>,
        payload: Payload,
    ): Pair<List<CatalogProduct>, MergeStats> {
        val hasAny = payload.byUuid.isNotEmpty() || payload.byName.isNotEmpty() ||
            payload.byNameExact.isNotEmpty() || payload.byGroupId.isNotEmpty() ||
            payload.nameGroupId.isNotEmpty() || payload.categoryNames.isNotEmpty() ||
            payload.byArticle.isNotEmpty() || payload.addonCategoryIds.isNotEmpty() ||
            payload.groupByProductId.isNotEmpty() || payload.groupByUuid.isNotEmpty()
        if (!hasAny) return products to MergeStats(total = products.size, unmatched = products.size)

        var stats = MergeStats(total = products.size)
        val merged = products.map { p ->
            val byUuid = payload.byUuid[p.uuid]
            val articleKey = p.article?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val byArticle = articleKey?.let { payload.byArticle[it] }
            val byExact = payload.byNameExact[Names.normalizeExact(p.name)]
            val byName = payload.byName[Names.normalize(p.name)]
            // identity-совпадение разрешает скрытие и группировку; имя — только enrich.
            val trusted = byUuid ?: byArticle
            val e = trusted ?: byExact ?: byName
            stats = when {
                byUuid != null -> stats.copy(byUuid = stats.byUuid + 1)
                byArticle != null -> stats.copy(byArticle = stats.byArticle + 1)
                byExact != null -> stats.copy(byExactName = stats.byExactName + 1)
                byName != null -> stats.copy(byName = stats.byName + 1)
                else -> stats.copy(unmatched = stats.unmatched + 1)
            }

            // Явная группа: identity → локальная → groups[] с сервера → legacy exact-name.
            // Stripped-имя (byName) для группировки НЕ используется: риск склеить чужие товары.
            val serverGroup = e?.productId?.let { payload.groupByProductId[it] } ?: payload.groupByUuid[p.uuid]
            val groupId = trusted?.groupId
                ?: p.groupId
                ?: serverGroup
                ?: byExact?.groupId
                // legacy: stripped-имя уникально резолвится в одну группу (донор добавок)
                ?: payload.nameGroupId[Names.normalize(p.name)]

            val tops = (e?.toppings ?: emptyList())
                .ifEmpty { groupId?.let { payload.byGroupId[it] } ?: emptyList() }
                .ifEmpty { p.toppings }
            if (e == null && tops.isEmpty()) return@map p
            val featured = if (e != null) {
                p.featured || e.featured ||
                    ProductMarkers.isFocus(e.description) ||
                    ProductMarkers.isCombo(p.name, e.description)
            } else {
                p.featured
            }
            val hiddenByFlag = trusted?.hideFromMenu == true || trusted?.isGroup == true
            val hiddenByCategory = e?.categoryId?.let { it in payload.addonCategoryIds } == true
            if (hiddenByFlag) stats = stats.copy(hiddenByFlag = stats.hiddenByFlag + 1)
            if (hiddenByCategory && !hiddenByFlag) stats = stats.copy(hiddenByCategory = stats.hiddenByCategory + 1)
            if (groupId != null && p.variantGroup == null) stats = stats.copy(groupedExplicit = stats.groupedExplicit + 1)

            p.copy(
                imageUrl = e?.imageUrl ?: p.imageUrl,
                recipe = e?.recipe?.takeIf { it.isNotBlank() } ?: p.recipe,
                toppings = tops,
                upsell = e?.upsell?.ifEmpty { p.upsell } ?: p.upsell,
                countsAsCupServer = e?.countsAsCup,
                // Приоритет серверу: freeEligible — прямой флаг «можно в подарок».
                // Если сервер прислал только countsAsCup, берём его (напиток, не еда).
                // Эвристика FreeCupRules остаётся только когда сервер молчит.
                freeEligible = when {
                    e?.freeEligible != null -> e.freeEligible
                    e?.countsAsCup != null && e.isFood != true -> e.countsAsCup
                    else -> p.freeEligible
                },
                // Если в Эвотор товар без parent-группы — подставляем категорию 6.7.
                groupName = p.groupName?.takeIf { it.isNotBlank() } ?: e?.categoryName,
                isFood = if (e != null) FoodRules.isFood(p.name, p.groupName, e.categoryName, e.description ?: p.description, e.isFood) else p.isFood,
                featured = featured,
                // Схема добавок с backend = можно открывать item-sheet с опциями.
                supportsMods = p.supportsMods || tops.isNotEmpty(),
                // Скрытие: локальный флаг, identity-флаг, либо id категории-добавки.
                // Совпадение по названию НЕ скрывает: обычный товар с тем же именем остаётся.
                hideFromMenu = p.hideFromMenu || hiddenByFlag || hiddenByCategory,
                // Группа — только по явной связи (см. выше), иначе метки из DESCRIPTION.
                variantGroup = groupId?.let { "admin-group:$it" } ?: p.variantGroup,
                sizeMl = (trusted ?: byExact)?.let { en ->
                    en.sizeValue?.takeIf { en.sizeUnit.equals("ml", true) || en.sizeUnit.isNullOrBlank() }
                } ?: p.sizeMl,
                optLabel = e?.variantLabel?.takeIf { it.isNotBlank() } ?: p.optLabel,
                groupId = groupId,
                categoryName = e?.categoryName ?: p.categoryName,
            )
        }
        return applySoftFocus(merged) to stats
    }

    /**
     * Если ни у одного товара нет featured — поднимаем в «Фокус» до 6 позиций с фото
     * (или первые 6 напитков freeEligible), чтобы блок не пропадал после синка.
     */
    fun applySoftFocus(list: List<CatalogProduct>): List<CatalogProduct> {
        if (list.isEmpty() || list.any { it.featured }) return list
        val preferred = list.filter { !it.imageUrl.isNullOrBlank() }
            .ifEmpty { list.filter { it.freeEligible } }
            .ifEmpty { list }
            .take(6)
            .map { it.uuid }
            .toSet()
        if (preferred.isEmpty()) return list
        return list.map { if (it.uuid in preferred) it.copy(featured = true) else it }
    }

    internal fun parsePayload(body: String): Payload {
        val root = JSONObject(body)
        val arr = root.optJSONArray("products") ?: JSONArray()
        val map = HashMap<String, Enrich>()
        val named = LinkedHashMap<String, Enrich>()
        val ambiguousNames = HashSet<String>()
        val namedExact = LinkedHashMap<String, Enrich>()
        val ambiguousExactNames = HashSet<String>()
        val byArticle = LinkedHashMap<String, Enrich>()
        val ambiguousArticles = HashSet<String>()
        val enrichAll = ArrayList<Enrich>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val uuid = o.optString("evotorUuid").trim()
            val productName = o.optString("name").trim()
            val recipe = o.optString("recipeText").ifBlank { o.optString("recipe") }.ifBlank { null }
            val desc = o.optString("description").ifBlank { null }
            val featured = o.optBoolean("featured", false) ||
                ProductMarkers.isFocus(desc) ||
                ProductMarkers.isCombo(o.optString("name"), desc)
            val enrich = Enrich(
                productId = if (o.has("productId")) o.optInt("productId") else o.optInt("id"),
                name = productName.ifBlank { null },
                imageUrl = o.optString("imageUrl").ifBlank { null },
                recipe = recipe,
                countsAsCup = if (o.has("countsAsCup")) o.optBoolean("countsAsCup") else null,
                freeEligible = if (o.has("freeEligible")) o.optBoolean("freeEligible") else null,
                toppings = parseToppings(o.optJSONArray("toppings")),
                upsell = parseUpsell(o.optJSONArray("upsell")),
                categoryName = o.optString("categoryName").ifBlank { null },
                featured = featured,
                description = desc,
                isFood = if (o.has("isFood") && !o.isNull("isFood")) o.optBoolean("isFood") else null,
                groupId = if (o.has("groupId") && !o.isNull("groupId")) o.optInt("groupId") else null,
                variantLabel = o.optString("variantLabel").ifBlank { null },
                variantSort = if (o.has("variantSort")) o.optInt("variantSort") else null,
                sizeValue = if (o.has("sizeValue") && !o.isNull("sizeValue")) o.optInt("sizeValue") else null,
                sizeUnit = o.optString("sizeUnit").ifBlank { null },
                hideFromMenu = o.optBoolean("hideFromMenu", false)
                    || o.optBoolean("isModifier", false)
                    || o.optBoolean("isGroup", false),
                isGroup = o.optBoolean("isGroup", false),
                parentProductId = if (o.has("parentProductId") && !o.isNull("parentProductId")) o.optInt("parentProductId") else null,
                articleNumber = o.optString("articleNumber").trim().ifBlank { null },
                categoryId = if (o.has("categoryId") && !o.isNull("categoryId")) o.optInt("categoryId") else null,
            )
            enrichAll.add(enrich)
            if (uuid.isNotEmpty()) map[uuid] = enrich
            val articleKey = enrich.articleNumber?.lowercase()
            if (!articleKey.isNullOrEmpty()) {
                if (byArticle.containsKey(articleKey)) ambiguousArticles.add(articleKey) else byArticle[articleKey] = enrich
            }
            val nameKey = Names.normalize(productName)
            if (nameKey.isNotEmpty()) {
                if (named.containsKey(nameKey)) ambiguousNames.add(nameKey) else named[nameKey] = enrich
            }
            val exactKey = Names.normalizeExact(productName)
            if (exactKey.isNotEmpty()) {
                if (namedExact.containsKey(exactKey)) {
                    // Два разных товара на один точный ключ — не клеить наугад.
                    if (!ambiguousExactNames.contains(exactKey)) {
                        Log.w("CatalogApi", "catalog name collision on exact key '$exactKey' — skip name fallback")
                    }
                    ambiguousExactNames.add(exactKey)
                } else {
                    namedExact[exactKey] = enrich
                }
            }
        }
        ambiguousNames.forEach { named.remove(it) }
        ambiguousExactNames.forEach { namedExact.remove(it) }
        // Артикул должен быть уникальным: при дубле не угадываем.
        ambiguousArticles.forEach { byArticle.remove(it) }
        // groups[]: страховка, если у товара в products[] не оказалось groupId.
        val groupByProductId = LinkedHashMap<Int, Int>()
        val groupByUuid = LinkedHashMap<String, Int>()
        root.optJSONArray("groups")?.let { gs ->
            for (gi in 0 until gs.length()) {
                val g = gs.optJSONObject(gi) ?: continue
                val gid = g.optInt("id", 0)
                if (gid <= 0) continue
                val vars = g.optJSONArray("variants") ?: continue
                for (vi in 0 until vars.length()) {
                    val v = vars.optJSONObject(vi) ?: continue
                    if (v.has("productId") && !v.isNull("productId")) {
                        val pid = v.optInt("productId", 0)
                        if (pid > 0 && !groupByProductId.containsKey(pid)) groupByProductId[pid] = gid
                    }
                    val vu = v.optString("evotorUuid").trim()
                    if (vu.isNotEmpty() && !groupByUuid.containsKey(vu)) groupByUuid[vu] = gid
                }
            }
        }
        val addonCategoryIds = LinkedHashSet<Int>()
        root.optJSONArray("addonCategoryIds")?.let { a ->
            for (i in 0 until a.length()) {
                val id = a.optInt(i, 0)
                if (id > 0) addonCategoryIds.add(id)
            }
        }
        // Group-донор: groupId → первый enrich с непустыми toppings;
        // и резолв неоднозначного stripped-имени к единственной группе (если группа одна).
        val groupTops = LinkedHashMap<Int, List<Topping>>()
        val strippedToGroups = HashMap<String, MutableSet<Int>>()
        for (e in enrichAll) {
            val g = e.groupId ?: continue
            if (e.toppings.isNotEmpty() && !groupTops.containsKey(g)) groupTops[g] = e.toppings
            val stripped = Names.normalize(e.name)
            if (stripped.isNotEmpty()) strippedToGroups.getOrPut(stripped) { HashSet() }.add(g)
        }
        val nameGroupId = HashMap<String, Int>()
        for ((stripped, groups) in strippedToGroups) {
            if (groups.size == 1) nameGroupId[stripped] = groups.first()
        }
        val catNames = ArrayList<String>()
        val cats = root.optJSONArray("categories")
        if (cats != null) {
            for (i in 0 until cats.length()) {
                val n = cats.getJSONObject(i).optString("name").trim()
                if (n.isNotEmpty()) catNames.add(n)
            }
        }
        // Fallback: unique categoryName from products order
        if (catNames.isEmpty()) {
            val seen = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i).optString("categoryName").trim()
                if (n.isNotEmpty()) seen.add(n)
            }
            catNames.addAll(seen)
        }
        val upsellDefault = parseUpsell(root.optJSONArray("upsellDefault"))
        val storeEcho = root.optString("storeUuid").trim().ifBlank { null }
        val menu = parseMenu(root.optJSONObject("menu"))
        return Payload(
            byUuid = map,
            upsellDefault = upsellDefault,
            categoryNames = catNames,
            byName = named,
            byNameExact = namedExact,
            byGroupId = groupTops,
            nameGroupId = nameGroupId,
            storeUuid = storeEcho,
            byArticle = byArticle,
            addonCategoryIds = addonCategoryIds,
            groupByProductId = groupByProductId,
            groupByUuid = groupByUuid,
            menu = menu,
        )
    }

    /** `menu` = { showAllTab, order[], hidden[] }. Нет блока — `null` (старый backend / старый кэш). */
    private fun parseMenu(o: JSONObject?): Menu? {
        if (o == null) return null
        // Контракт: «показывать, если не false».
        return Menu(
            showAllTab = o.optBoolean("showAllTab", true),
            order = parseNameList(o.optJSONArray("order")),
            hidden = parseNameList(o.optJSONArray("hidden")),
        )
    }

    /** Список имён групп: trim, без пустых, без дублей (регистр не важен). */
    internal fun parseNameList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val name = a.optString(i).trim()
            if (name.isNotEmpty() && out.none { it.equals(name, ignoreCase = true) }) out.add(name)
        }
        return out
    }

    private fun parseToppings(a: JSONArray?): List<Topping> = ToppingJson.parse(a)

    private fun parseUpsell(a: JSONArray?): List<UpsellItem> {
        if (a == null) return emptyList()
        val out = ArrayList<UpsellItem>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val price = when {
                o.has("priceRub") -> o.optDouble("priceRub", 0.0)
                else -> o.optDouble("price", 0.0)
            }
            out.add(
                UpsellItem(
                    productId = if (o.has("productId")) o.optInt("productId") else o.optInt("id"),
                    evotorUuid = o.optString("evotorUuid").ifBlank { null },
                    name = o.optString("name"),
                    priceRub = BigDecimal.valueOf(price),
                    imageUrl = o.optString("imageUrl").ifBlank { null },
                )
            )
        }
        return out
    }
}
