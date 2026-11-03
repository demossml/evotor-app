package ru.sixthcup.evotor.ui

/**
 * Отладочная панель в модальном окне товара: видно, что происходит с
 * `GET /api/devices/products/{id}/configuration` — без adb и logcat.
 *
 * Текст собирается чистой функцией ([lines]) из состояния [State], поэтому проверяется тестами
 * и одинаково выглядит в любом месте листа.
 */
object ConfigSheetDebug {

    /**
     * Флаг в одном месте: `false` убирает панель целиком (одна правка и пересборка).
     * Пока `true` — владелец читает причину прямо на экране кассы.
     */
    const val DEBUG_CONFIG_SHEET = true

    /** Фаза запроса. */
    enum class Phase { OPENED, WAITING, DONE, FAILED }

    data class State(
        val versionName: String,
        val versionCode: Int,
        val phase: Phase = Phase.OPENED,
        /** `productId` из пути; `null` — запрос не отправлялся. */
        val productId: Int? = null,
        val storeUuid: String? = null,
        val requested: Boolean = false,
        val httpStatus: Int? = null,
        val elapsedMs: Long? = null,
        /** Ошибка транспорта/разбора/`ok:false`/`links` — текстом. */
        val error: String? = null,
        val fromCache: Boolean = false,
        val volumes: Int = 0,
        val milkOptions: Int? = null,
        val additionOptions: Int? = null,
        val configErrors: List<String> = emptyList(),
        /** Чем сопоставлен объём: `uuid` / `productId` / `size` / `name` / `none`. */
        val matchBy: String = "none",
        val matchPid: Int? = null,
        val terminalUuid: String? = null,
        val uiMilk: Int = 0,
        val uiSyrup: Int = 0,
        val uiSugar: Int = 0,
        val uiExtra: Int = 0,
        val needOpts: Boolean = false,
        val configPresent: Boolean = false,
    ) {
        val uiAdditions: Int get() = uiSyrup + uiSugar + uiExtra
    }

    /** Короткий store: полный uuid в лист не влезает. */
    private fun shortStore(raw: String?): String =
        if (raw.isNullOrBlank()) "-" else if (raw.length <= 14) raw else raw.take(8) + "…" + raw.takeLast(4)

    private fun shortUuid(raw: String?): String =
        if (raw.isNullOrBlank()) "-" else if (raw.length <= 14) raw else raw.take(6) + "…" + raw.takeLast(4)

    /**
     * Строки панели. Ошибка всегда помечена `ERROR:` — её нельзя не заметить.
     */
    fun lines(s: State): List<String> {
        val out = ArrayList<String>(8)
        out.add("1. Sheet open · v${s.versionName} (${s.versionCode})")

        out.add(
            when {
                s.productId == null || s.productId <= 0 ->
                    "2. Request skipped: нет productId товара (обновите каталог)"
                s.storeUuid.isNullOrBlank() ->
                    "2. Request skipped: не определён storeUuid точки"
                !s.requested -> "2. Request… productId=${s.productId} store=${shortStore(s.storeUuid)}"
                else -> "2. Request productId=${s.productId} store=${shortStore(s.storeUuid)}"
            },
        )

        out.add(
            when (s.phase) {
                Phase.OPENED, Phase.WAITING -> "3. WAIT: ответа пока нет…"
                Phase.FAILED -> "3. ERROR: " + (s.error ?: "запрос конфигурации не удался")
                Phase.DONE -> buildString {
                    append("3. HTTP ").append(s.httpStatus ?: 0)
                    s.elapsedMs?.let { append(" in ").append(it).append("ms") }
                    if (s.fromCache) append(" · fromCache=true")
                }
            },
        )

        out.add(
            // Тело показываем только когда ответ реально получен: пока ждём — прочерк.
            if (s.phase == Phase.DONE) {
                "4. Body: volumes=${s.volumes} milk=${s.milkOptions ?: "null"} " +
                    "additions=${s.additionOptions ?: "null"} " +
                    "errors=[${s.configErrors.joinToString(",")}]"
            } else {
                "4. Body: —"
            },
        )

        out.add(
            "5. Match volume: by=${s.matchBy} pid=${s.matchPid ?: "-"} " +
                "terminalUuid=${shortUuid(s.terminalUuid)}",
        )
        out.add("6. UI opts: milk=${s.uiMilk} syrup=${s.uiSyrup} sugar=${s.uiSugar} extra=${s.uiExtra}")
        out.add("7. Policy needOpts=${s.needOpts} configPresent=${s.configPresent}")

        if (s.error != null && s.phase != Phase.FAILED) out.add("ERROR: ${s.error}")
        return out
    }

    fun text(s: State): String = lines(s).joinToString("\n")
}
