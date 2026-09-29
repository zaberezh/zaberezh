package by.zaberezh.forma.core.ai

import by.zaberezh.forma.core.food.FoodItem
import by.zaberezh.forma.core.food.FoodResolver
import by.zaberezh.forma.core.food.Macro
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
import com.anthropic.models.messages.Message
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
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).apply {
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
            if (!haiku) outputConfig(OutputConfig.builder().effort(effort).build()) // Haiku не поддерживает effort
            // Серверный fallback при отказе классификатора (поддерживается новыми моделями).
            if (official && model in FALLBACK_MODELS) {
                putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
        }

    private fun call(b: MessageCreateParams.Builder): Message {
        val m = client.messages().create(b.build())
        runCatching { used += m.usage().inputTokens() + m.usage().outputTokens() }
        if (m.stopReason().orElse(null) == StopReason.REFUSAL) error("Claude отказался отвечать на запрос")
        return m
    }

    private fun text(m: Message) = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()

    /** Разбор приёма пищи в позиции с КБЖУ. Для заведений Минска ищет данные в интернете. */
    /** Уровни: 2 = поиск + открытие страниц (точные КБЖУ с карточек товаров), 1 = только поиск, 0 = без интернета. */
    fun foods(meal: String): List<FoodItem> {
        var level = 2
        while (true) {
            try { return foods(meal, level) } catch (e: BadRequestException) {
                if (level == 0) throw e
                level-- // посредник не поддерживает инструмент — пробуем проще
            }
        }
    }

    private fun foods(meal: String, level: Int): List<FoodItem> {
        val web = level >= 1
        val loc = UserLocation.builder().city("Minsk").country("BY").timezone("Europe/Minsk").build()
        val b = base(FOOD_SYSTEM, OutputConfig.Effort.LOW)
            .apply {
                if (web && modernSearch) addTool(WebSearchTool20260209.builder().maxUses(MAX_SEARCHES).userLocation(loc).build())
                else if (web) addTool(WebSearchTool20250305.builder().maxUses(MAX_SEARCHES).userLocation(loc).build())
                // открыть карточку товара/меню — точные цифры вместо средних; объём страницы ограничен
                if (level >= 2 && modernSearch) addTool(WebFetchTool20260209.builder().maxUses(MAX_FETCHES).maxContentTokens(FETCH_TOKENS).build())
                else if (level >= 2) addTool(WebFetchTool20250910.builder().maxUses(MAX_FETCHES).maxContentTokens(FETCH_TOKENS).build())
            }
            .addTool(REPORT_TOOL)
            .addUserMessage(meal)
        // Каждый повтор заново отправляет результаты поиска — поэтому не больше 3 запросов.
        var last = ""
        repeat(3) {
            val m = call(b)
            last = text(m)
            val tool = m.content().firstNotNullOfOrNull { it.toolUse().orElse(null)?.takeIf { t -> t.name() == "report_foods" } }
            if (tool != null) {
                val r = LENIENT.decodeFromJsonElement(FoodReport.serializer(), toJson(tool._input().convert(Map::class.java)))
                val items = r.items.filter { it.grams > 0 && it.kcal >= 0 }.map {
                    val k = 100.0 / it.grams
                    FoodItem(it.name, it.grams, Macro(it.kcal * k, it.protein * k, it.fat * k, it.carbs * k), it.source, it.confidence)
                }
                if (items.isEmpty()) error("Модель не вернула ни одной позиции" + r.note.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
                return items
            }
            b.addMessage(m) // pause_turn: сервер продолжит сам; end_turn без инструмента — напоминаем
            if (m.stopReason().orElse(null) != StopReason.PAUSE_TURN)
                b.addUserMessage("Верни результат вызовом report_foods. Если точных данных нет — дай оценку, items не может быть пустым.")
        }
        error("Модель не вернула результат" + last.takeIf { it.isNotBlank() }?.let { ": ${it.take(300)}" }.orEmpty())
    }

    /**
     * План тренировки на день. Один-два запроса без веб-поиска, ответ ограничен 8000 токенами.
     * [limit] — потолок токенов на весь вызов: при превышении останавливаемся.
     */
    fun planDay(method: String, prompt: String, limit: Long = 200_000): Pair<List<Pair<String, Int>>, String> {
        val b = base(method, OutputConfig.Effort.LOW).maxTokens(8000L).addTool(PLAN_TOOL).addUserMessage(prompt)
        repeat(2) {
            if (used > limit) error("Остановлено: израсходовано $used токенов (лимит $limit)")
            val m = call(b)
            val tool = m.content().firstNotNullOfOrNull { it.toolUse().orElse(null)?.takeIf { t -> t.name() == "report_plan" } }
            if (tool != null) {
                val r = LENIENT.decodeFromJsonElement(PlanReport.serializer(), toJson(tool._input().convert(Map::class.java)))
                return r.items.map { it.id to it.sets.coerceIn(1, 6) } to r.note
            }
            b.addMessage(m)
            if (m.stopReason().orElse(null) != StopReason.PAUSE_TURN) b.addUserMessage("Верни план вызовом report_plan.")
        }
        error("Модель не вернула план")
    }

    /** Анализ чекапа по методике. */
    fun analyze(method: String, report: String, facts: String, note: String): String {
        val m = call(
            base(method, OutputConfig.Effort.MEDIUM)
                .addUserMessage("Отчёт приложения:\n$report\n\nДанные (JSON):\n$facts\n\nКомментарий пользователя: ${note.ifBlank { "—" }}")
        )
        return text(m).ifBlank { error("Пустой ответ модели") }
    }

    fun resolver() = FoodResolver { foods(it) }

    /** Модели, доступные у провайдера (если он поддерживает /v1/models). */
    fun models(): List<String> = client.models().list().data().map { it.id() }

    /** Минимальный запрос для проверки ключа/адреса/модели. */
    fun ping(): String {
        val m = call(MessageCreateParams.builder().model(model).maxTokens(1024L).addUserMessage("Ответь одним словом: ок"))
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
        const val MAX_SEARCHES = 4L
        const val MAX_FETCHES = 2L
        const val FETCH_TOKENS = 6000L
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
            is AnthropicServiceException -> "Ошибка сервера ${e.statusCode()}: ${e.message?.take(200)}"
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
            - Базовые продукты без бренда (яйцо, гречка, куриная грудка…) — справочные значения без поиска.
              Крупы и макароны — в готовом виде, если не сказано «сухой»/«сырой».
            Правила:
            - Не больше 3 поисков и 2 открытых страниц на весь запрос; не нашёл точного — оцени и честно пометь.
            - source: адрес страницы, откуда взяты цифры, или «оценка: …» с кратким обоснованием.
            - confidence: high — цифры со страницы именно этого товара/блюда; medium — близкий аналог; low — оценка.
            - Если количество не указано — масса упаковки/стандартной порции. Напитки тоже позиции.
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
