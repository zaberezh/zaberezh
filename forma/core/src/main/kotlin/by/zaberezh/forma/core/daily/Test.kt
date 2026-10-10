package by.zaberezh.forma.core.daily

import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.Module
import by.zaberezh.forma.core.Section
import by.zaberezh.forma.core.store.Kind
import by.zaberezh.forma.core.store.Store
import by.zaberezh.forma.core.store.ZONE
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Периодический тест (максимум подтягиваний за подход раз в неделю и т.п.). */
@Serializable data class TestDef(val id: String, val title: String, val everyDays: Int = 7, val unit: String = "раз", val hint: String = "")

@Serializable data class TestResult(val test: String, val date: String, val value: Double)

val TEST = Kind("daily.test", TestResult.serializer())

object TestModule : Module {
    override val id = "tests"
    override val title = "Тесты"

    /** Тест «максимум за подход» ↔ ежедневный счётчик, из подходов которого его можно взять. */
    private val FROM_COUNTER = mapOf("pullups_max" to "pullups")

    /** Только то, что записано как тест (для «пора проверить максимум»). */
    fun recorded(s: Store, test: String): List<Pair<LocalDate, Double>> =
        TEST.all(s).filter { it.second.test == test }.map { LocalDate.parse(it.second.date) to it.second.value }.sortedBy { it.first }

    /**
     * Результаты по дням: записанный тест, а в дни без теста — лучший подход из счётчика за этот день
     * (максимум подтягиваний за раз, который внёс).
     */
    fun results(s: Store, test: String): List<Pair<LocalDate, Double>> {
        val own = recorded(s, test)
        val counter = FROM_COUNTER[test] ?: return own
        val days = own.map { it.first }.toSet()
        val fromSets = COUNT.all(s).map { it.second }.filter { it.counter == counter }
            .mapNotNull { d -> (d.sets.maxOrNull() ?: d.n.takeIf { it > 0 })?.let { LocalDate.parse(d.date) to it.toDouble() } }
            .filter { it.first !in days }
        return (own + fromSets).sortedBy { it.first }
    }

    /** Один результат на день — повторная запись за день заменяет прежнюю. */
    fun record(s: Store, test: String, day: LocalDate, value: Double) {
        TEST.save(s, TestResult(test, day.toString(), value), ts = day.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli(), id = "test:$test:$day")
    }

    fun delete(s: Store, test: String, day: LocalDate) = s.delete("test:$test:$day")

    /** Дней с последнего теста (null — не было). */
    fun sinceLast(ctx: Ctx, test: String): Long? = recorded(ctx.store, test).lastOrNull()?.let { ChronoUnit.DAYS.between(it.first, ctx.today) }

    fun due(ctx: Ctx, def: TestDef): Boolean = (sinceLast(ctx, def.id) ?: Long.MAX_VALUE) >= def.everyDays

    override fun morning(ctx: Ctx): List<String> =
        ctx.settings.tests.filter { it.everyDays > 1 && due(ctx, it) }.map { d ->   // ежедневные — без утреннего напоминания
            val last = results(ctx.store, d.id).lastOrNull()
            "Тест: ${d.title}" + (last?.let { " (прошлый ${fmt(it.second)} ${d.unit}, ${it.first})" } ?: "")
        }

    override fun checkup(ctx: Ctx, from: LocalDate, to: LocalDate): Section? {
        val lines = mutableListOf<String>(); val facts = mutableMapOf<String, Any?>()
        ctx.settings.tests.forEach { d ->
            val all = results(ctx.store, d.id)
            val inside = all.filter { it.first in from..to }
            if (inside.isEmpty()) return@forEach
            val before = all.lastOrNull { it.first < from }
            val first = before ?: inside.first()
            val last = inside.last()
            lines += "${d.title}: ${fmt(first.second)} → ${fmt(last.second)} ${d.unit} (${first.first} → ${last.first})"
            facts[d.id] = all.takeLast(8).map { mapOf("date" to it.first.toString(), "value" to it.second) }
        }
        return if (lines.isEmpty()) null else Section(title, lines, emptyList(), facts)
    }

    fun fmt(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else "%.1f".format(v)
}
