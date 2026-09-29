package by.zaberezh.forma.core.gym

import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.Module
import by.zaberezh.forma.core.Section
import by.zaberezh.forma.core.body.latestWeight
import by.zaberezh.forma.core.pct
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.slope
import by.zaberezh.forma.core.store.Entry
import by.zaberezh.forma.core.store.Store
import by.zaberezh.forma.core.store.ZONE
import by.zaberezh.forma.core.store.endMs
import by.zaberezh.forma.core.store.startMs
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.min

fun e1rm(w: Double, r: Int, bwLoad: Double = 0.0) = (w + bwLoad) * (1 + min(r, 15) / 30.0)

data class ExTrend(val ex: Exercise, val n: Int, val first: Double, val last: Double, val best: Double, val pctWeek: Double?, val status: String)

fun LocalDate.ofMs(ms: Long): LocalDate = LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZONE)

object GymModule : Module {
    override val id = "gym"
    override val title = "Зал"

    fun program(s: Store) = PROGRAM.get(s)
    fun workouts(s: Store, from: LocalDate? = null, to: LocalDate? = null): List<Pair<Entry, Workout>> =
        WORKOUT.all(s, from?.startMs() ?: Long.MIN_VALUE, to?.endMs() ?: Long.MAX_VALUE)

    /** Дни, засчитанные как тренировка: есть записанные подходы или визит в зал ≥ minVisitMin. */
    fun trainedDays(ctx: Ctx, from: LocalDate, to: LocalDate): Set<LocalDate> {
        val w = workouts(ctx.store, from, to).filter { it.second.sets.isNotEmpty() }.map { it.first.day }
        val v = VISIT.all(ctx.store, from.startMs(), to.endMs())
            .filter { it.second.minutes >= ctx.settings.minVisitMin }.map { it.first.day }
        return (w + v).toSet()
    }

    private fun planKey(mon: LocalDate) = "gym.plan.$mon"

    /** Дни, выбранные вручную на неделю (null — авто-план). */
    fun manualDays(s: Store, mon: LocalDate): Set<LocalDate>? =
        s.kvGet(planKey(mon))?.split(",")?.filter { it.isNotBlank() }?.map(LocalDate::parse)?.toSet()

    fun week(ctx: Ctx): WeekPlan {
        val mon = ctx.today.with(DayOfWeek.MONDAY)
        val trained = trainedDays(ctx, mon, mon.plusDays(6))
        val manual = manualDays(ctx.store, mon)
        return if (manual != null) manualWeek(trained, manual, ctx.today, ctx.settings.sessionsPerWeek)
        else planWeek(trained, ctx.today, ctx.settings.sessionsPerWeek)
    }

    /**
     * Поставить/снять тренировку на день текущей недели. Возвращает текст ошибки или null.
     * Первое ручное изменение фиксирует текущий авто-план и дальше меняется только вручную.
     */
    fun toggleDay(ctx: Ctx, day: LocalDate): String? {
        val mon = ctx.today.with(DayOfWeek.MONDAY)
        if (day < ctx.today) return "Прошедший день не перенести"
        if (day.dayOfWeek.value > 5) return "Только будни"
        val trained = trainedDays(ctx, mon, mon.plusDays(6))
        if (day in trained) return "Тренировка в этот день уже засчитана"
        val wk = week(ctx)
        val current = wk.plan.filter { it >= ctx.today && it !in trained }.toSet()
        val next = if (day in current) current - day else current + day
        if (!noLongRun(trained + next, mon)) return "Будет 3 дня подряд — так нельзя"
        ctx.store.kvPut(planKey(mon), next.sorted().joinToString(","))
        return null
    }

    fun resetWeek(ctx: Ctx) = ctx.store.kvPut(planKey(ctx.today.with(DayOfWeek.MONDAY)), null)

    /** Следующий день программы по кругу A→B→C. */
    fun nextDay(s: Store): TrainingDay {
        val p = program(s)
        val last = workouts(s).lastOrNull { it.second.sets.isNotEmpty() }?.second?.day
        if (p.days.isEmpty()) return TrainingDay("A", "A", emptyList())
        val i = p.days.indexOfFirst { it.id == last }
        return p.days[(i + 1).mod(p.days.size)]
    }

    fun history(s: Store, exId: String, before: Long = Long.MAX_VALUE): List<Pair<LocalDate, List<SetLog>>> =
        workouts(s).filter { it.first.ts < before }
            .map { (e, w) -> e.day to w.sets.filter { it.ex == exId } }
            .filter { it.second.isNotEmpty() }

    fun targets(s: Store, day: TrainingDay, before: Long = Long.MAX_VALUE): List<Target> {
        val p = program(s)
        return day.exercises.mapNotNull(p::ex).map { ex -> nextTarget(ex, history(s, ex.id, before).map { it.second }) }
    }

    fun trend(s: Store, ex: Exercise, to: LocalDate, days: Long = 42): ExTrend {
        val bw = (latestWeight(s) ?: 70.0) * ex.bw
        val pts = history(s, ex.id).filter { it.first > to.minusDays(days) && it.first <= to }
            .map { (d, sets) -> d to sets.maxOf { e1rm(it.w, it.r, bw) } }
        if (pts.isEmpty()) return ExTrend(ex, 0, 0.0, 0.0, 0.0, null, "нет данных")
        val mean = pts.map { it.second }.average()
        val k = slope(pts.map { ChronoUnit.DAYS.between(to, it.first).toDouble() to it.second })
        val pct = k?.let { it * 7 / mean * 100 }
        val status = when {
            pts.size < 3 || pct == null -> "мало данных"
            pct >= 1.0 -> "растёт"
            pct >= 0.3 -> "медленно"
            pct > -1.0 -> "стоит"
            else -> "падает"
        }
        return ExTrend(ex, pts.size, pts.first().second, pts.last().second, pts.maxOf { it.second }, pct, status)
    }

    /** Подходы на мышцу за период (≥5 повторов; косвенные с коэффициентом). */
    fun volume(s: Store, from: LocalDate, to: LocalDate): Map<String, Double> {
        val p = program(s)
        val out = HashMap<String, Double>()
        workouts(s, from, to).flatMap { it.second.sets }.filter { it.r >= 5 }.forEach { set ->
            p.ex(set.ex)?.muscles?.forEach { (m, k) -> out[m] = (out[m] ?: 0.0) + k }
        }
        return out
    }

    override fun morning(ctx: Ctx): List<String> {
        val wk = week(ctx)
        if (!wk.todayGym) return emptyList()
        val day = nextDay(ctx.store)
        val head = "Зал сегодня: день ${day.name} (${wk.done + 1}/${ctx.settings.sessionsPerWeek} за неделю)" +
            if (!wk.canSkipToday) " — перенос сорвёт недельную цель" else ""
        return listOf(head) + targets(ctx.store, day).map { it.short() }
    }

    override fun checkup(ctx: Ctx, from: LocalDate, to: LocalDate): Section {
        val s = ctx.store
        val p = program(s)
        val weeks = (ChronoUnit.DAYS.between(from, to) + 1) / 7.0
        val done = trainedDays(ctx, from, to).size
        val plan = (weeks * ctx.settings.sessionsPerWeek).toInt()
        val lines = mutableListOf("Тренировок: $done из $plan")
        val actions = mutableListOf<String>()
        if (done < plan * 0.8) actions += "Посещаемость $done/$plan — это главный ограничитель прогресса сейчас."

        val used = workouts(s, from, to).flatMap { it.second.sets.map(SetLog::ex) }.toSet()
        val trends = p.exercises.filter { it.id in used }.map { trend(s, it, to) }
        trends.forEach { t ->
            lines += "${t.ex.name}: e1RM ${t.first.r1()}→${t.last.r1()} кг" +
                (t.pctWeek?.let { " (${it.pct()}/нед)" } ?: "") + " — ${t.status}"
        }
        val judged = trends.filter { it.status != "мало данных" && it.status != "нет данных" }
        val bad = judged.filter { it.status == "стоит" || it.status == "падает" }
        if (judged.size >= 3 && bad.size * 2 >= judged.size)
            actions += "Системный застой (${bad.size}/${judged.size} упражнений): сначала питание (белок, калории) и сон; " +
                "если в норме — разгрузочная неделя: те же веса, −40% подходов."
        else bad.forEach { actions += "${it.ex.name}: ${it.status}. Смени диапазон повторов или вариацию на 4–6 недель." }

        val vol = volume(s, from, to).mapValues { it.value / weeks }
        val volLine = MUSCLES.keys.mapNotNull { m -> vol[m]?.let { "${MUSCLES[m]} ${it.r1()}" } }
        if (volLine.isNotEmpty()) lines += "Подходов в неделю: " + volLine.joinToString(", ")
        p.volume.forEach { (m, range) ->
            val v = vol[m] ?: 0.0
            if (done > 0 && range[0] > 0 && v < range[0]) actions += "Мало объёма: ${MUSCLES[m] ?: m} ${v.r1()}/нед (цель ${range[0]}–${range[1]})."
            if (range[1] > 0 && v > range[1] * 1.25) actions += "Лишний объём: ${MUSCLES[m] ?: m} ${v.r1()}/нед (цель ${range[0]}–${range[1]}) — при плохом сне это мешает восстановлению."
        }
        val facts = mapOf(
            "sessions" to done, "planned" to plan,
            "exercises" to trends.map { mapOf("id" to it.ex.id, "n" to it.n, "e1rm_first" to it.first, "e1rm_last" to it.last, "pct_week" to it.pctWeek, "status" to it.status) },
            "sets_per_week" to vol,
        )
        return Section(title, lines, actions, facts)
    }
}
