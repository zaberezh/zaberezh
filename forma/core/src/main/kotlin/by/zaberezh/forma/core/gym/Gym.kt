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

    /** Дни, засчитанные как тренировка: есть записанные подходы. */
    fun trainedDays(ctx: Ctx, from: LocalDate, to: LocalDate): Set<LocalDate> =
        workouts(ctx.store, from, to).filter { it.second.sets.isNotEmpty() }.map { it.first.day }.toSet()

    private fun planKey(mon: LocalDate) = "gym.plan.$mon"

    /** Дни, выбранные вручную на неделю (null — авто-план). */
    fun manualDays(s: Store, mon: LocalDate): Set<LocalDate>? =
        s.kvGet(planKey(mon))?.split(",")?.filter { it.isNotBlank() }?.map(LocalDate::parse)?.toSet()

    fun week(ctx: Ctx): WeekPlan {
        val mon = ctx.today.with(DayOfWeek.MONDAY)
        val trained = trainedDays(ctx, mon, mon.plusDays(6))
        val manual = manualDays(ctx.store, mon)
        return if (manual != null) manualWeek(trained, manual, ctx.today, ctx.settings.sessionsPerWeek)
        else planWeek(trained, ctx.today, ctx.settings.sessionsPerWeek, weekends = ctx.settings.gymWeekends)
    }

    /**
     * Поставить/снять тренировку на день текущей недели. Возвращает текст ошибки или null.
     * Первое ручное изменение фиксирует текущий авто-план и дальше меняется только вручную.
     * 3 дня подряд разрешены (UI предупреждает через [threeInRow]).
     */
    fun toggleDay(ctx: Ctx, day: LocalDate): String? {
        val mon = ctx.today.with(DayOfWeek.MONDAY)
        if (day < ctx.today) return "Прошедший день не перенести"
        if (day.dayOfWeek.value > 5 && !ctx.settings.gymWeekends) return "Выходные выключены в настройках"
        val trained = trainedDays(ctx, mon, mon.plusDays(6))
        if (day in trained) return "Тренировка в этот день уже засчитана"
        val wk = week(ctx)
        val current = wk.plan.filter { it >= ctx.today && it !in trained }.toSet()
        val next = if (day in current) current - day else current + day
        ctx.store.kvPut(planKey(mon), next.sorted().joinToString(","))
        return null
    }

    /** В плане недели 3+ дня подряд. */
    fun threeInRow(ctx: Ctx): Boolean = !noLongRun(week(ctx).plan.toSet(), ctx.today.with(DayOfWeek.MONDAY))

    fun resetWeek(ctx: Ctx) = ctx.store.kvPut(planKey(ctx.today.with(DayOfWeek.MONDAY)), null)

    fun history(s: Store, exId: String, before: Long = Long.MAX_VALUE): List<Pair<LocalDate, List<SetLog>>> =
        workouts(s).filter { it.first.ts < before }
            .map { (e, w) -> e.day to w.sets.filter { it.ex == exId } }
            .filter { it.second.isNotEmpty() }

    // ---------- план на дату ----------
    private fun planId(d: LocalDate) = "plan:$d"

    fun storedPlan(s: Store, d: LocalDate): DayPlan? = s.get(planId(d))?.let(DAYPLAN::decode)

    fun savePlan(s: Store, plan: DayPlan) {
        val d = LocalDate.parse(plan.date)
        DAYPLAN.save(s, plan, ts = d.startMs(), id = planId(d))
    }

    /** Вернуть авто-план (сбросить ручные правки дня). */
    fun resetPlan(s: Store, d: LocalDate) = s.delete(planId(d))

    /** Выполненные сессии: дата → подходы. */
    fun sessions(s: Store): List<Pair<LocalDate, List<SetLog>>> =
        workouts(s).filter { it.second.sets.isNotEmpty() }.map { it.first.day to it.second.sets }

    private fun lastUsed(s: Store): Map<String, LocalDate> {
        val m = HashMap<String, LocalDate>()
        sessions(s).forEach { (d, sets) -> sets.forEach { m[it.ex] = d } }
        return m
    }

    fun split(ctx: Ctx): List<DayType> = splitDays(ctx.settings.split)

    /**
     * Тип дня по очереди сплита: после последней тренировки — следующий. Считаются прошедшие тренировки
     * (их тип берётся из сохранённого плана) и запланированные дни недели до этой даты.
     */
    fun dayType(ctx: Ctx, date: LocalDate): DayType {
        val days = split(ctx)
        if (days.size == 1) return days[0]
        fun typeOf(d: LocalDate) = storedPlan(ctx.store, d)?.day?.let { id -> days.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        typeOf(date)?.let { return days[it] }
        val trained = trainedDays(ctx, ctx.today.minusDays(28), ctx.today)
        val upcoming = week(ctx).plan.filter { it >= ctx.today && it !in trained }
        var idx = -1
        (trained + upcoming).filter { it < date }.sorted().forEach { d -> idx = typeOf(d) ?: ((idx + 1) % days.size) }
        return days[(idx + 1) % days.size]
    }

    /**
     * План на дату: сохранённый (правили руками / тренировка начата) или составленный заново.
     * day — тип дня сплита вручную (иначе по очереди; при акценте — день, куда акцент входит).
     * Для будущих дней учитываются запланированные до них тренировки недели (симуляция), чтобы дни не повторялись.
     */
    fun planFor(ctx: Ctx, date: LocalDate, focus: String? = null, day: String? = null): DayPlan {
        val s = ctx.store
        val p0 = program(s)
        if (focus == null && day == null) storedPlan(s, date)?.let { return it.copy(items = Planner.arrange(p0, it.items, ctx.settings.supersets, it.focus)) }
        val p = program(s)
        val log = Planner.muscleLog(p, sessions(s)).toMutableList()
        val used = lastUsed(s).toMutableMap()
        val trained = trainedDays(ctx, ctx.today.minusDays(7), ctx.today)
        week(ctx).plan.filter { it >= ctx.today && it < date && it !in trained }.forEach { d ->
            val pl = planFor(ctx, d)
            log += d to Planner.planMuscles(p, pl)
            pl.items.forEach { used[it.ex] = d }
        }
        val days = split(ctx)
        val focusSet = focus?.let { FOCUS[it]?.second } ?: emptySet()
        // акцент переносит день в тот тип сплита, где акцентных мышц больше всего («Ноги» в день «Верх» → «Низ»)
        fun overlap(d: DayType) = d.muscles.count { it in focusSet }
        val best = days.maxOf(::overlap)
        val type = days.firstOrNull { it.id == day }
            ?: (storedPlan(s, date)?.day?.let { id -> days.firstOrNull { it.id == id } } ?: dayType(ctx, date)).takeIf { overlap(it) == best }
            ?: days.first { overlap(it) == best }
        val plan = Planner.build(p, date, log, used, ctx.settings.sessionsPerWeek, focus, type, days, Planner.budget(ctx.settings.sessionMin))
        return plan.copy(items = Planner.arrange(p, plan.items, ctx.settings.supersets, focus))
    }

    /** Цели по упражнениям плана (двойная прогрессия; число подходов — из плана). */
    fun targets(s: Store, plan: DayPlan, before: Long = Long.MAX_VALUE): List<Target> {
        val p = program(s)
        return plan.items.mapNotNull { i -> p.ex(i.ex)?.copy(sets = i.sets) }
            .map { ex -> nextTarget(ex, history(s, ex.id, before).map { it.second }) }
    }

    /** Текущая сила по упражнению: последний рабочий вес × повторы (или стартовые из базы). */
    fun strength(s: Store, ex: Exercise): String {
        val last = history(s, ex.id).lastOrNull()?.second?.let(::workingSets)
        return when {
            last != null && last.isNotEmpty() -> "${last.first().w.r1()} кг × ${last.joinToString(",") { it.r.toString() }}"
            ex.startWeight != null -> "${ex.startWeight.r1()} кг × ${ex.repMin}"
            else -> "вес не задан"
        }
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
        val plan = planFor(ctx, ctx.today)
        val head = "Зал сегодня: ${Planner.summary(program(ctx.store), plan).ifEmpty { "план пуст — добавь упражнения в базу" }}" +
            " (${wk.done + 1}/${ctx.settings.sessionsPerWeek} за неделю)" + if (!wk.canSkipToday) " — перенос сорвёт недельную цель" else ""
        return listOf(head) + targets(ctx.store, plan).map { it.short() }
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
