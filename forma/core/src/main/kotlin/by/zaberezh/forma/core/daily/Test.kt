package by.zaberezh.forma.core.daily

import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.Module
import by.zaberezh.forma.core.Section
import by.zaberezh.forma.core.gym.planPace
import by.zaberezh.forma.core.slope
import by.zaberezh.forma.core.store.Kind
import by.zaberezh.forma.core.store.Store
import by.zaberezh.forma.core.store.ZONE
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Периодический тест (максимум подтягиваний за подход раз в неделю и т.п.). */
@Serializable data class TestDef(val id: String, val title: String, val everyDays: Int = 7, val unit: String = "раз", val hint: String = "")

@Serializable data class TestResult(val test: String, val date: String, val value: Double)

/**
 * Темп роста максимума: лучший результат каждой недели (по записанным тестам), прирост в неделю по регрессии и план. weeks — конец недели → лучший результат, от старых к новым.
 */
data class RepsPace(val weeks: List<Pair<LocalDate, Double>>, val perWeek: Double?, val plan: Double, val status: String) {
    /** Где по плану быть через [n] недель от нынешнего лучшего. */
    fun goal(n: Int): Double? = weeks.lastOrNull()?.let { it.second + plan * n }
}

val TEST = Kind("daily.test", TestResult.serializer())

object TestModule : Module {
    override val id = "tests"
    override val title = "Тесты"

    /** Тесты «раз за подход» (подтягивания): план роста в них считается по формуле Эпли, а не процентом от результата. */
    private val REPS = setOf("pullups_max")

    /** Записанные результаты теста по дням — только то, что ввёл сам (лучший подход дня тестом не считается). */
    fun recorded(s: Store, test: String): List<Pair<LocalDate, Double>> =
        TEST.all(s).filter { it.second.test == test }.map { LocalDate.parse(it.second.date) to it.second.value }.sortedBy { it.first }

    /** Результаты теста — только введённые вручную. */
    fun results(s: Store, test: String): List<Pair<LocalDate, Double>> = recorded(s, test)

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

    /**
     * Темп за [weeks] недель. План — тот же плановый рост силы, что в зале (по стажу): для подтягиваний +1% силы —
     * это +(30 + повторы)/100 раза за подход по формуле Эпли (при 10 разах ≈ +0,4 в неделю, раз в 2–3 недели +1);
     * для других тестов — +план% от результата.
     */
    fun pace(ctx: Ctx, test: String, weeks: Int = 6): RepsPace {
        val all = results(ctx.store, test)
        val best = (weeks - 1 downTo 0).mapNotNull { i ->
            val end = ctx.today.minusDays(7L * i)
            all.filter { it.first > end.minusDays(7) && it.first <= end }.maxOfOrNull { it.second }?.let { end to it }
        }
        val cur = best.lastOrNull()?.second ?: return RepsPace(best, null, 0.0, "нет данных")
        val pct = planPace(ctx.settings.gymLevel)
        val unit = if (test in REPS) (30 + cur) / 100 else cur / 100   // сколько раз — это 1% силы
        val plan = pct * unit
        val perWeek = slope(best.map { ChronoUnit.DAYS.between(ctx.today, it.first).toDouble() to it.second })?.times(7)
        val status = when {
            best.size < 3 || perWeek == null -> "мало данных"
            perWeek >= plan - 1e-9 -> "растёт"
            perWeek >= plan * 0.3 -> "медленно"
            perWeek > -unit -> "стоит"
            else -> "падает"
        }
        return RepsPace(best, perWeek, plan, status)
    }

    fun fmt(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else "%.1f".format(v)
}
