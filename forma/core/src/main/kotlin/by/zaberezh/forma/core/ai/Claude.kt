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
import com.anthropic.models.messages.WebSearchTool20260209
import kotlinx.serialization.Serializable

/** Тонкая обёртка над Claude API: поиск КБЖУ (веб-поиск) и разбор чекапа. */
class Claude(apiKey: String, private val model: String, baseUrl: String = "") {
    private val official = baseUrl.isBlank() || "api.anthropic.com" in baseUrl
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).apply {
        // посредники принимают ключ либо в x-api-key, либо в Authorization: Bearer — шлём оба
        if (!official) baseUrl(baseUrl.trim().trimEnd('/').removeSuffix("/v1")).authToken(apiKey)
    }.build()

    private fun base(system: String, effort: OutputConfig.Effort) = MessageCreateParams.builder()
        .model(model)
        .maxTokens(16000L)
        .system(system)
        .outputConfig(OutputConfig.builder().effort(effort).build())
        .apply {
            // Серверный fallback при отказе классификатора (поддерживается новыми моделями).
            if (official && model in FALLBACK_MODELS) {
                putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
        }

    private fun check(m: Message) {
        if (m.stopReason().orElse(null) == StopReason.REFUSAL) error("Claude отказался отвечать на запрос")
    }

    private fun text(m: Message) = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()

    /** Разбор приёма пищи в позиции с КБЖУ. Для заведений Минска ищет данные в интернете. */
    fun foods(meal: String): List<FoodItem> = try {
        foods(meal, web = true)
    } catch (e: BadRequestException) {
        foods(meal, web = false) // посредник без серверного веб-поиска — оценка по знаниям модели
    }

    private fun foods(meal: String, web: Boolean): List<FoodItem> {
        val b = base(FOOD_SYSTEM, OutputConfig.Effort.MEDIUM)
            .apply {
                if (web) addTool(WebSearchTool20260209.builder().maxUses(6L)
                    .userLocation(UserLocation.builder().city("Minsk").country("BY").timezone("Europe/Minsk").build()).build())
            }
            .addTool(REPORT_TOOL)
            .addUserMessage(meal)
        repeat(6) {
            val m = client.messages().create(b.build())
            check(m)
            val call = m.content().firstNotNullOfOrNull { it.toolUse().orElse(null)?.takeIf { t -> t.name() == "report_foods" } }
            if (call != null) {
                val r = JSON.decodeFromJsonElement(FoodReport.serializer(), toJson(call._input().convert(Map::class.java)))
                return r.items.filter { it.grams > 0 }.map {
                    val k = 100.0 / it.grams
                    FoodItem(it.name, it.grams, Macro(it.kcal * k, it.protein * k, it.fat * k, it.carbs * k), it.source, it.confidence)
                }
            }
            b.addMessage(m) // pause_turn: сервер продолжит сам; end_turn без инструмента — напоминаем
            if (m.stopReason().orElse(null) != StopReason.PAUSE_TURN) b.addUserMessage("Верни результат вызовом report_foods.")
        }
        error("Claude не вернул результат")
    }

    /** Анализ чекапа по методике. */
    fun analyze(method: String, report: String, facts: String, note: String): String {
        val m = client.messages().create(
            base(method, OutputConfig.Effort.HIGH)
                .addUserMessage("Отчёт приложения:\n$report\n\nДанные (JSON):\n$facts\n\nКомментарий пользователя: ${note.ifBlank { "—" }}")
                .build()
        )
        check(m)
        return text(m)
    }

    fun resolver() = FoodResolver { foods(it) }

    /** Минимальный запрос для проверки ключа/адреса/модели. */
    fun ping(): String {
        val m = client.messages().create(MessageCreateParams.builder().model(model).maxTokens(256L).addUserMessage("Ответь одним словом: ок").build())
        return text(m).ifBlank { "ок (${m.model()})" }
    }

    @Serializable private data class FoodReport(val items: List<Item>, val note: String = "")
    @Serializable private data class Item(
        val name: String, val grams: Double, val kcal: Double, val protein: Double, val fat: Double, val carbs: Double,
        val source: String = "", val confidence: String = "",
    )

    companion object {
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
