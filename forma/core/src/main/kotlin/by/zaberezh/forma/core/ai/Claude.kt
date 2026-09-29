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
    fun foods(meal: String): List<FoodItem> = try {
        foods(meal, web = true)
    } catch (e: BadRequestException) {
        foods(meal, web = false) // посредник без серверного веб-поиска — оценка по знаниям модели
    }

    private fun foods(meal: String, web: Boolean): List<FoodItem> {
        val loc = UserLocation.builder().city("Minsk").country("BY").timezone("Europe/Minsk").build()
        val b = base(FOOD_SYSTEM, OutputConfig.Effort.LOW)
            .apply {
                if (web && modernSearch) addTool(WebSearchTool20260209.builder().maxUses(MAX_SEARCHES).userLocation(loc).build())
                else if (web) addTool(WebSearchTool20250305.builder().maxUses(MAX_SEARCHES).userLocation(loc).build())
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

    /** Анализ чекапа по методике. */
    fun analyze(method: String, report: String, facts: String, note: String): String {
        val m = call(
            base(method, OutputConfig.Effort.MEDIUM)
                .addUserMessage("Отчёт приложения:\n$report\n\nДанные (JSON):\n$facts\n\nКомментарий пользователя: ${note.ifBlank { "—" }}")
        )
        return text(m).ifBlank { error("Пустой ответ модели") }
    }

    fun resolver() = FoodResolver { foods(it) }

    /** Минимальный запрос для проверки ключа/адреса/модели. */
    fun ping(): String {
        val m = call(MessageCreateParams.builder().model(model).maxTokens(1024L).addUserMessage("Ответь одним словом: ок"))
        return text(m).ifBlank { "ок" } + " · модель ${m.model()}"
    }

    @Serializable private data class FoodReport(val items: List<Item>, val note: String = "")
    @Serializable private data class Item(
        val name: String, val grams: Double, val kcal: Double, val protein: Double, val fat: Double, val carbs: Double,
        val source: String = "", val confidence: String = "",
    )

    companion object {
        const val MAX_SEARCHES = 3L
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
            - Если указано заведение, сеть или бренд (шаурма из конкретной точки, блюдо кафе, продукт марки) — найди в интернете
              официальные данные: сайт/меню заведения, карточку на сервисах доставки (Яндекс Еда, Delivio, Wolt и др.), этикетку.
              Вес порции бери из меню. В source укажи адрес страницы.
            - Если официальных данных нет — оцени по составу и типичному весу порции именно этого заведения;
              confidence = medium или low, в source начни с «оценка:» и кратко обоснуй.
            - Базовые продукты (яйцо, гречка, куриная грудка, хлеб…) считай по справочным значениям без поиска.
              Крупы и макароны — в готовом виде, если не сказано «сухой»/«сырой».
            - Если количество не указано — стандартная порция. Напитки тоже позиции (кола, сок, кофе с молоком/сахаром).
            - Экономь: не больше 2 поисков на весь запрос; если за 2 поиска не нашёл — оценивай.
            - items никогда не бывает пустым: если заведение не найдено, оцени по типичному блюду такого типа.
            Результат верни только вызовом инструмента report_foods, без текста.
        """.trimIndent()

        private fun prop(type: String, desc: String, extra: Map<String, Any> = emptyMap()) = mapOf("type" to type, "description" to desc) + extra

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
