package by.zaberezh.forma.core.ai

import by.zaberezh.forma.core.food.FoodItem
import by.zaberezh.forma.core.food.FoodResolver
import by.zaberezh.forma.core.food.Macro
import by.zaberezh.forma.core.food.ShopPage
import by.zaberezh.forma.core.report.toJson
import by.zaberezh.forma.core.store.JSON
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebFetchTool20250910
import com.anthropic.models.messages.WebFetchTool20260209
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
import kotlinx.serialization.Serializable

/** Тонкая обёртка над Claude API: поиск КБЖУ (веб-поиск) и разбор чекапа. */
class Claude(apiKey: String, private val model: String, baseUrl: String = "") {
    private val official = baseUrl.isBlank() || "api.anthropic.com" in baseUrl
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(1)
        .timeout(java.time.Duration.ofSeconds(90)).apply {   // по умолчанию 10 мин — поиск «висел»
        // посредники принимают ключ либо в x-api-key, либо в Authorization: Bearer — шлём оба
        if (!official) baseUrl(baseUrl.trim().trimEnd('/').removeSuffix("/v1")).authToken(apiKey)
    }.build()

    /** Токены, потраченные этим клиентом (вход + выход) — показываем пользователю. */
    var used = 0L
        private set

    private val haiku = "haiku" in model
    /** Новый веб-поиск с фильтрацией результатов (меньше токенов) есть у моделей 4.6+; Haiku — только базовый. */
    private val modernSearch = !haiku && listOf("-4-6", "-4-7", "-4-8", "-5").any { it in model }

    private fun base(system: String, effort: OutputConfig.Effort) = MessageCreateParams.builder()
        .model(model)
        .maxTokens(16000L)
        .system(system)
        .apply {
            // Haiku не поддерживает effort; в режиме совместимости не шлём ничего необязательного
            if (!haiku && level > 0) outputConfig(OutputConfig.builder().effort(effort).build())
            // Серверный fallback при отказе классификатора (поддерживается новыми моделями).
            if (official && model in FALLBACK_MODELS) {
                putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
        }

    private fun call(b: MessageCreateParams.Builder): Message {
        val params = b.build()
        runCatching {
            val mapper = Class.forName("com.anthropic.core.ObjectMappers").getMethod("jsonMapper").invoke(null) as com.fasterxml.jackson.databind.ObjectMapper
            val body = mapper.writeValueAsString(params._body())
            val msgs = body.substringAfter("\"messages\":", "").take(400)
            debug("→ ${MODE_NAMES[level]} · ${body.length} симв. · модель $model · messages: $msgs")
        }
        val m = try { client.messages().create(params) } catch (e: Exception) {
            debug("${MODE_NAMES[level]} · ОШИБКА ${(e as? AnthropicServiceException)?.statusCode() ?: ""}: ${e.message?.take(500)}")
            throw e
        }
        runCatching { used += m.usage().inputTokens() + m.usage().outputTokens() }
        runCatching {
            debug("${MODE_NAMES[level]} · stop=${m.stopReason().orElse(null)} · вход ${m.usage().inputTokens()} / выход ${m.usage().outputTokens()} · " +
                m.content().joinToString(", ") { b -> when {
                    b.isText() -> "text«${b.asText().text().take(200)}»"
                    b.isToolUse() -> "tool ${b.asToolUse().name()} ${b.asToolUse()._input().toString().take(300)}"
                    b.isServerToolUse() -> "server_tool"
                    b.isWebSearchToolResult() -> "search_result"
                    b.isWebFetchToolResult() -> "fetch_result"
                    b.isThinking() -> "thinking"
                    else -> "other"
                } })
        }
        if (m.stopReason().orElse(null) == StopReason.REFUSAL) error("Claude отказался отвечать на запрос")
        return m
    }

    /** Сообщение пользователя массивом блоков: строковый content некоторые посредники теряют. */
    private fun MessageCreateParams.Builder.userText(t: String) = addUserMessageOfBlockParams(listOf(ContentBlockParam.ofText(t)))

    private fun text(m: Message) = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()

    /** Разбор приёма пищи в позиции с КБЖУ. Для заведений Минска ищет данные в интернете. */
    /** Уровни: 2 = поиск + открытие страниц (точные КБЖУ с карточек товаров), 1 = только поиск, 0 = без интернета. */
    /** Текущий уровень возможностей провайдера (см. [startLevel]). */
    private var level = startLevel

    /** Ошибка «провайдер не умеет»: запрос отклонён или сбой на стороне посредника (не ключ, не права, не лимит). */
    private fun unsupported(e: Throwable) =
        e is AnthropicServiceException && e.statusCode() !in setOf(401, 403, 429)

    /** Выполнить с понижением уровня при «не умеет»; найденный рабочий уровень запоминается до перезапуска. */
    private fun <T> withFallback(web: Boolean = true, block: () -> T): T {
        while (true) {
            try { return block() } catch (e: Exception) {
                if (e is NoWebTools) { level = 1; startLevel = 1; debug("веб-поиск посредника не работает → без него"); continue }
                if (!unsupported(e) || level == 0) throw e
                // 4xx — провайдер точно не умеет: запоминаем. 5xx может быть разовым сбоем — упрощаем только этот запрос.
                val permanent = (e as AnthropicServiceException).statusCode() in 400..499
                if (web) { level--; if (permanent) startLevel = level } else level = 0
            }
        }
    }

    /** Режим последнего запроса — показываем пользователю. */
    val mode: String get() = MODE_NAMES[level]

    /** [hints] — страницы магазина, найденные приложением: модель берёт цифры оттуда, если это тот продукт. */
    fun foods(meal: String, hints: List<ShopPage> = emptyList(), web: List<String> = emptyList()): List<FoodItem> =
        withFallback { foodsAt(meal, hints, web) }

    /** Посредник не выполняет серверный веб-поиск (модель вызвала поиск, а результатов нет). */
    private class NoWebTools : RuntimeException("посредник не поддерживает веб-поиск")

    private fun foodsAt(meal: String, hints: List<ShopPage>, webHints: List<String>): List<FoodItem> {
        val web = level >= 2
        val loc = UserLocation.builder().city("Minsk").country("BY").timezone("Europe/Minsk").build()
        // текст еды дублируется в system: если посредник всё же потеряет сообщение, модель его увидит
        val hintText = if (hints.isEmpty()) "" else "\n\nСтраницы edostavka.by, найденные приложением (если это тот продукт — бери цифры отсюда, source = url):\n" +
            hints.joinToString("\n") { "${it.url} | ${it.title} | ${it.snippet.take(220)}" }
        val webText = if (webHints.isEmpty()) "" else "\n\nНайдено в интернете приложением (выдача поисковика, используй, если подходит):\n" +
            webHints.joinToString("\n") { "— ${it.take(300)}" }
        val b = base(FOOD_SYSTEM + "\n\nЗапрос пользователя (что он съел): «$meal»" + hintText + webText, OutputConfig.Effort.LOW)
            .apply {
                if (web && modernSearch) addTool(WebSearchTool20260209.builder().maxUses(MAX_SEARCHES).userLocation(loc).build())
                else if (web) addTool(WebSearchTool20250305.builder().maxUses(MAX_SEARCHES).userLocation(loc).build())
                // открыть карточку товара/меню — точные цифры вместо средних; объём страницы ограничен
                if (level >= 3 && modernSearch) addTool(WebFetchTool20260209.builder().maxUses(MAX_FETCHES).maxContentTokens(FETCH_TOKENS).build())
                else if (level >= 3) addTool(WebFetchTool20250910.builder().maxUses(MAX_FETCHES).maxContentTokens(FETCH_TOKENS).build())
            }
            .addTool(if (level > 0) REPORT_TOOL else REPORT_TOOL.toBuilder().strict(false).build())
            .userText("Я съел: «$meal». Посчитай КБЖУ каждой позиции.")
        // Каждый повтор заново отправляет результаты поиска — поэтому не больше 3 запросов.
        var last = ""
        repeat(3) {
            val m = call(b)
            last = text(m)
            val tool = m.content().firstNotNullOfOrNull { it.toolUse().orElse(null)?.takeIf { t -> t.name() == "report_foods" } }
            // модель пошла искать, а посредник поиск не выполнил → дальше без серверного веб-поиска
            if (tool == null && web && m.content().any { it.isServerToolUse() } &&
                m.content().none { it.isWebSearchToolResult() || it.isWebFetchToolResult() }) throw NoWebTools()
            if (tool != null) {
                val r = LENIENT.decodeFromJsonElement(FoodReport.serializer(), toJson(tool._input().convert(Map::class.java)))
                val items = r.items.filter { it.grams > 0 && it.kcal >= 0 }.map {
                    val k = 100.0 / it.grams
                    FoodItem(it.name, it.grams, Macro(it.kcal * k, it.protein * k, it.fat * k, it.carbs * k), it.source, it.confidence)
                }
                // заготовка вместо ответа («Пример позиции», «требуется запрос») = модель не увидела текст
                val stub = items.any { it.name.startsWith("Пример") || it.source.contains("запрос пользователя") }
                if (items.isNotEmpty() && !stub) return items
                // пустой ответ: переспрашиваем с исходным текстом (ответ на tool_use обязателен)
                b.addMessage(m).addUserMessageOfBlockParams(listOf(
                    ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tool.id())
                        .content("Пусто — так нельзя.").isError(true).build()),
                    ContentBlockParam.ofText("Текст пользователя: «$meal». Это название продукта/блюда (возможно бренд). " +
                        (if (web) "Найди его КБЖУ в интернете (e-dostavka.by, сайт производителя)" else "Определи продукт по названию") +
                        " и верни через report_foods; если точно не найти — дай оценку по похожему продукту с confidence=low."),
                ))
                last = r.note
                return@repeat
            }
            b.addMessage(m) // pause_turn: сервер продолжит сам; end_turn без инструмента — напоминаем
            if (m.stopReason().orElse(null) != StopReason.PAUSE_TURN)
                b.userText("Верни результат вызовом report_foods. Если точных данных нет — дай оценку, items не может быть пустым.")
        }
        if (web) throw NoWebTools() // 3 попытки с поиском впустую — пробуем без него
        error("Модель не вернула результат" + last.takeIf { it.isNotBlank() }?.let { ": ${it.take(300)}" }.orEmpty())
    }

    /**
     * КБЖУ по фото еды: модель определяет блюда, оценивает массу порций по фото (тарелка, приборы, упаковка)
     * и КБЖУ. Без веб-поиска — один-два запроса. [note] — подпись пользователя («это гречка с курицей», «вес 350 г»).
     */
    fun foodsFromPhoto(jpeg: ByteArray, note: String = ""): List<FoodItem> = withFallback(web = false) { photoAt(jpeg, note) }

    private fun photoAt(jpeg: ByteArray, note: String): List<FoodItem> {
        val img = ContentBlockParam.ofImage(ImageBlockParam.builder().source(Base64ImageSource.builder()
            .data(java.util.Base64.getEncoder().encodeToString(jpeg)).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()).build())
        val ask = "На фото — моя еда" + (if (note.isNotBlank()) " (подпись: «$note»)" else "") +
            ". Определи каждое блюдо и продукт, оцени массу каждой порции по фото (размер тарелки, приборов, упаковки) " +
            "и КБЖУ именно этой порции. Если в подписи указан вес — используй его. Верни через report_foods; source = «оценка по фото»."
        val b = base(FOOD_SYSTEM, OutputConfig.Effort.LOW)
            .addTool(if (level > 0) REPORT_TOOL else REPORT_TOOL.toBuilder().strict(false).build())
            .addUserMessageOfBlockParams(listOf(img, ContentBlockParam.ofText(ask)))
        repeat(2) {
            val m = call(b)
            val tool = m.content().firstNotNullOfOrNull { it.toolUse().orElse(null)?.takeIf { t -> t.name() == "report_foods" } }
            if (tool != null) {
                val r = LENIENT.decodeFromJsonElement(FoodReport.serializer(), toJson(tool._input().convert(Map::class.java)))
                val items = r.items.filter { it.grams > 0 && it.kcal >= 0 }.map {
                    val k = 100.0 / it.grams
                    FoodItem(it.name, it.grams, Macro(it.kcal * k, it.protein * k, it.fat * k, it.carbs * k), it.source.ifBlank { "оценка по фото" }, it.confidence)
                }
                if (items.isEmpty()) error("На фото не получилось распознать еду" + r.note.takeIf { it.isNotBlank() }?.let { ": ${it.take(200)}" }.orEmpty())
                return items
            }
            b.addMessage(m)
            if (m.stopReason().orElse(null) != StopReason.PAUSE_TURN) b.userText("Верни результат вызовом report_foods.")
        }
        error("Модель не вернула результат по фото")
    }

    /**
     * План тренировки на день. Один-два запроса без веб-поиска, ответ ограничен 8000 токенами.
     * [limit] — потолок токенов на весь вызов: при превышении останавливаемся.
     */
    fun planDay(method: String, prompt: String, limit: Long = 200_000): Pair<List<Pair<String, Int>>, String> =
        withFallback(web = false) { planDayAt(method, prompt, limit) }

    private fun planDayAt(method: String, prompt: String, limit: Long): Pair<List<Pair<String, Int>>, String> {
        val b = base(method, OutputConfig.Effort.LOW).maxTokens(8000L)
            .addTool(if (level > 0) PLAN_TOOL else PLAN_TOOL.toBuilder().strict(false).build()).userText(prompt)
        repeat(2) {
            if (used > limit) error("Остановлено: израсходовано $used токенов (лимит $limit)")
            val m = call(b)
            val tool = m.content().firstNotNullOfOrNull { it.toolUse().orElse(null)?.takeIf { t -> t.name() == "report_plan" } }
            if (tool != null) {
                val r = LENIENT.decodeFromJsonElement(PlanReport.serializer(), toJson(tool._input().convert(Map::class.java)))
                return r.items.map { it.id to it.sets.coerceIn(1, 6) } to r.note
            }
            b.addMessage(m)
            if (m.stopReason().orElse(null) != StopReason.PAUSE_TURN) b.userText("Верни план вызовом report_plan.")
        }
        error("Модель не вернула план")
    }

    /** Анализ чекапа по методике. */
    fun analyze(method: String, report: String, facts: String, note: String): String = withFallback(web = false) { analyzeAt(method, report, facts, note) }

    private fun analyzeAt(method: String, report: String, facts: String, note: String): String {
        val m = call(
            base(method, OutputConfig.Effort.MEDIUM)
                .userText("Отчёт приложения:\n$report\n\nДанные (JSON):\n$facts\n\nКомментарий пользователя: ${note.ifBlank { "—" }}")
        )
        return text(m).ifBlank { error("Пустой ответ модели") }
    }

    fun resolver() = FoodResolver { foods(it) }

    /** Модели, доступные у провайдера (если он поддерживает /v1/models). */
    fun models(): List<String> = client.models().list().data().map { it.id() }

    /** Минимальный запрос для проверки ключа/адреса/модели. */
    fun ping(): String {
        val m = call(MessageCreateParams.builder().model(model).maxTokens(1024L).userText("Ответь одним словом: ок"))
        return text(m).ifBlank { "ок" } + " · модель ${m.model()}"
    }

    @Serializable private data class PlanReport(val items: List<PlanEntry>, val note: String = "")
    @Serializable private data class PlanEntry(val id: String, val sets: Int)

    @Serializable private data class FoodReport(val items: List<Item>, val note: String = "")
    @Serializable private data class Item(
        val name: String, val grams: Double, val kcal: Double, val protein: Double, val fat: Double, val carbs: Double,
        val source: String = "", val confidence: String = "",
    )

    companion object {
        /**
         * Уровни: 3 — поиск + открытие страниц, 2 — только поиск, 1 — без интернета,
         * 0 — режим совместимости (без effort и строгих схем). Стартуем с последнего рабочего.
         */
        @Volatile var startLevel = 3
        val MODE_NAMES = listOf("совместимость", "без интернета", "поиск", "поиск + страницы")

        /** Журнал последних ответов API — кнопка «Скопировать отладку». */
        private val log = ArrayDeque<String>()
        @Synchronized fun debug(line: String) { log.addLast(line); while (log.size > 12) log.removeFirst() }
        @Synchronized fun debugText(): String = "Grind отладка API\n" + log.joinToString("\n")

        const val MAX_SEARCHES = 5L
        const val MAX_FETCHES = 3L
        const val FETCH_TOKENS = 4000L   // КБЖУ на страницах товара/продукта — в начале, больше не нужно
        private val LENIENT = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        /** Модели на выбор в настройках: id → пояснение. */
        val MODELS = listOf(
            "claude-sonnet-5-5" to "Sonnet 5.5 — оптимально: умный поиск, дешевле Opus",
            "claude-opus-5-5" to "Opus 5.5 — точнее для чекапов, дороже",
            "claude-haiku-4-5" to "Haiku 4.5 — самая дешёвая, поиск проще (больше токенов на результатах)",
        )

        /** Понятное объяснение ошибки API. */
        fun explain(e: Throwable): String = when (e) {
            is UnauthorizedException -> "Ключ не принят (401). Проверь ключ и адрес API: ключ посредника работает только с его адресом."
            is PermissionDeniedException -> "Доступ запрещён (403): регион или права ключа. Нужен VPN или адрес посредника."
            is NotFoundException -> "Не найдено (404): неверный адрес API или модель недоступна у этого провайдера."
            is RateLimitException -> "Лимит запросов/баланс (429). Подожди или пополни баланс."
            is BadRequestException -> "Запрос отклонён (400): ${e.message?.take(200)}"
            is AnthropicServiceException -> if (e.statusCode() >= 500)
                "Ошибка провайдера ${e.statusCode()}: сбой у посредника или он не поддерживает запрос даже в простом режиме. ${e.message?.take(200)}"
            else "Ошибка сервера ${e.statusCode()}: ${e.message?.take(200)}"
            is AnthropicIoException -> "Нет связи с API: проверь интернет/VPN и адрес."
            else -> e.message ?: e.toString()
        }

        val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-opus-5")

        val FOOD_SYSTEM = """
            Ты модуль подсчёта КБЖУ в личном трекере. Пользователь живёт в Минске (Беларусь) и пишет, что съел.
            Разбей приём пищи на позиции. Для каждой определи массу порции в граммах и КБЖУ ИМЕННО ЭТОЙ ПОРЦИИ.
            Главное — ТОЧНЫЕ данные, а не средние. Среднее/типовое значение — только если точного источника нет.
            Где искать точные цифры:
            - Магазинные продукты (бренд, упаковка, готовая еда из магазина): карточка товара на e-dostavka.by
              (там указаны КБЖУ на 100 г и масса упаковки). Ищи запросом «<название> e-dostavka.by» и открой карточку.
              Если там нет — сайт производителя или другие магазины Беларуси (gippo-market.by, green-market.by).
            - Заведения (шаурма, кафе, фастфуд): сайт/меню заведения, карточка на сервисах доставки (Яндекс Еда, Delivio, Wolt).
              Вес порции бери из меню.
            - Базовые продукты без бренда и домашние блюда (яйцо, гречка, куриная грудка, борщ, сырники…):
              tablicakalorijnosti.ru — ищи «<продукт> tablicakalorijnosti» и открой страницу вида
              tablicakalorijnosti.ru/produkty/<название> (там КБЖУ на 100 г). Выбирай страницу, точно совпадающую
              по виду обработки: «вареная гречка на воде», а не «гречка сухая».
              Крупы и макароны — в готовом виде, если не сказано «сухой»/«сырой».
            Правила:
            - Не больше 4 поисков и 3 открытых страниц на весь запрос; одна страница — на позицию, самые важные позиции первыми.
              Не нашёл точного — оцени и честно пометь.
            - source: адрес страницы, откуда взяты цифры, или «оценка: …» с кратким обоснованием.
            - confidence: high — цифры со страницы именно этого товара/блюда; medium — близкий аналог; low — оценка.
            - Если количество не указано — масса стандартной порции блюда; для магазинного продукта «целиком» (йогурт, батончик) — упаковка.
              Напитки тоже позиции.
            - Добавки к блюду считаются ПОРЦИЕЙ, а не упаковкой, если не сказано иное: соус/кетчуп/майонез 20 г,
              сметана 30 г, варенье/джем 20 г, мёд 15 г, сыр 20 г (ломтик), сливочное масло 10 г, сгущёнка 20 г, сахар 5 г (ложка).
              Соус к блюду — отдельной позицией.
            - Фастфуд (хот-дог, шаурма, бургер) без указания заведения — типичная порция: состав по словам пользователя
              («без ничего» = только булка и сосиска), оценка по стандартным компонентам.
            - items никогда не бывает пустым.
            Результат верни только вызовом инструмента report_foods, без текста.
        """.trimIndent()

        private fun prop(type: String, desc: String, extra: Map<String, Any> = emptyMap()) = mapOf("type" to type, "description" to desc) + extra

        val PLAN_TOOL: Tool = Tool.builder()
            .name("report_plan")
            .description("Вернуть план тренировки: упражнения из базы по порядку выполнения и число рабочих подходов.")
            .strict(true)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("items", JsonValue.from(mapOf(
                                "type" to "array",
                                "items" to mapOf(
                                    "type" to "object", "additionalProperties" to false, "required" to listOf("id", "sets"),
                                    "properties" to mapOf(
                                        "id" to prop("string", "id упражнения из базы"),
                                        "sets" to prop("integer", "Рабочих подходов, 1–6"),
                                    ),
                                ),
                            )))
                            .putAdditionalProperty("note", JsonValue.from(prop("string", "Почему такой план, 1–2 предложения")))
                            .build()
                    )
                    .required(listOf("items", "note"))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build()
            )
            .build()

        val REPORT_TOOL: Tool = Tool.builder()
            .name("report_foods")
            .description("Вернуть разобранный приём пищи: позиции с массой порции и КБЖУ на порцию.")
            .strict(true)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("items", JsonValue.from(mapOf(
                                "type" to "array",
                                "items" to mapOf(
                                    "type" to "object",
                                    "additionalProperties" to false,
                                    "required" to listOf("name", "grams", "kcal", "protein", "fat", "carbs", "source", "confidence"),
                                    "properties" to mapOf(
                                        "name" to prop("string", "Название по-русски, с заведением/брендом, если есть"),
                                        "grams" to prop("number", "Масса порции, г (для напитков — мл)"),
                                        "kcal" to prop("number", "Ккал на порцию"),
                                        "protein" to prop("number", "Белки на порцию, г"),
                                        "fat" to prop("number", "Жиры на порцию, г"),
                                        "carbs" to prop("number", "Углеводы на порцию, г"),
                                        "source" to prop("string", "URL источника или «оценка: …»"),
                                        "confidence" to prop("string", "Уверенность", mapOf("enum" to listOf("high", "medium", "low"))),
                                    ),
                                ),
                            )))
                            .putAdditionalProperty("note", JsonValue.from(prop("string", "Короткое примечание или пустая строка")))
                            .build()
                    )
                    .required(listOf("items", "note"))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build()
            )
            .build()
    }
}
