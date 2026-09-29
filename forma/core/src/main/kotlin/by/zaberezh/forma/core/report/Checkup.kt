package by.zaberezh.forma.core.report

import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.Modules
import by.zaberezh.forma.core.Section
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.store.JSON
import by.zaberezh.forma.core.store.Kind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Serializable
data class CheckupRec(val from: String, val to: String, val text: String, val facts: String, val ai: String? = null)

val CHECKUP = Kind("checkup", CheckupRec.serializer())

object Checkup {
    fun sections(ctx: Ctx, from: LocalDate, to: LocalDate): List<Section> = Modules.all.mapNotNull { it.checkup(ctx, from, to) }

    fun render(from: LocalDate, to: LocalDate, sections: List<Section>): String = buildString {
        append("# Чекап $from — $to\n")
        sections.forEach { s ->
            append("\n## ${s.title}\n")
            s.lines.forEach { append("- $it\n") }
            if (s.actions.isNotEmpty()) { append("\n**Действия:**\n"); s.actions.forEach { append("- $it\n") } }
        }
    }

    /** Сжатые данные для ИИ/Claude-чата: профиль, программа, факты модулей. */
    fun facts(ctx: Ctx, from: LocalDate, to: LocalDate, sections: List<Section>): String {
        val p = GymModule.program(ctx.store)
        val m = mapOf(
            "period" to "$from..$to",
            "profile" to JSON.encodeToJsonElement(by.zaberezh.forma.core.Profile.serializer(), ctx.settings.profile),
            "program" to p.days.map { d -> d.name + ": " + d.exercises.mapNotNull(p::ex).joinToString { "${it.name} ${it.sets}x${it.repMin}-${it.repMax}" } },
            "modules" to sections.associate { it.title to it.facts },
        )
        return toJson(m).toString()
    }

    /** Дней с последнего чекапа (null — чекапов не было). */
    fun daysSinceLast(ctx: Ctx): Long? = CHECKUP.all(ctx.store).lastOrNull()?.let { ChronoUnit.DAYS.between(it.first.day, ctx.today) }

    fun firstDataDay(ctx: Ctx): LocalDate? =
        ctx.store.allTypes().mapNotNull { t -> ctx.store.list(t).firstOrNull()?.day }.minOrNull()

    fun due(ctx: Ctx): Boolean {
        val n = daysSinceLast(ctx)
        if (n != null) return n >= ctx.settings.checkupDays
        val first = firstDataDay(ctx) ?: return false
        return ChronoUnit.DAYS.between(first, ctx.today) >= ctx.settings.checkupDays
    }

    /** Строки утреннего уведомления со всех модулей. */
    fun morning(ctx: Ctx): List<String> = Modules.all.flatMap { it.morning(ctx) } + if (due(ctx)) listOf("Чекап: пора") else emptyList()
}

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Number -> JsonPrimitive(if (v is Double) Math.round(v * 100) / 100.0 else v)
    is Boolean -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
    is Iterable<*> -> JsonArray(v.map(::toJson))
    else -> JsonPrimitive(v.toString())
}
