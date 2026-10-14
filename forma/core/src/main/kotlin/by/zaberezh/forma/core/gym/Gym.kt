package by.zaberezh.forma.core.gym

import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.Module
import by.zaberezh.forma.core.Section
import by.zaberezh.forma.core.body.latestWeight
import by.zaberezh.forma.core.pct
import by.zaberezh.forma.core.r1
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

data class ExTrend(
    val ex: Exercise, val n: Int, val first: Double, val last: Double, val best: Double, val pctWeek: Double?, val status: String,
    val firstSet: SetLog? = null, val lastSet: SetLog? = null,   // лучший подход первой и последней тренировки периода
)

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

    /** Тренировки → по упражнениям: (время, день, подходы) — один проход на все упражнения, пока тренировки не менялись. */
    private val byExercise = by.zaberezh.forma.core.store.Memo { all: List<Pair<Entry, Workout>> ->
        val m = HashMap<String, MutableList<Triple<Long, LocalDate, List<SetLog>>>>()
        all.forEach { (e, w) -> w.sets.groupBy { it.ex }.forEach { (id, l) -> m.getOrPut(id) { mutableListOf() } += Triple(e.ts, e.day, l) } }
        m
    }

    fun history(s: Store, exId: String, before: Long = Long.MAX_VALUE): List<Pair<LocalDate, List<SetLog>>> =
        byExercise(workouts(s))[exId].orEmpty().filter { it.first < before }.map { it.second to it.third }

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
    fun sessions(s: Store): List<Pair<LocalDate, List<SetLog>>> = sessionsOf(workouts(s))
    private val sessionsOf = by.zaberezh.forma.core.store.Memo { all: List<Pair<Entry, Workout>> ->
        all.filter { it.second.sets.isNotEmpty() }.map { it.first.day to it.second.sets }
    }

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
     * План на дату: сохранённый (правили руками / тренировка начата) или из плана всей недели ([weekPlans]).
     * day — тип дня сплита вручную (иначе по очереди; при акценте — день, куда акцент входит).
     */
    fun planFor(ctx: Ctx, date: LocalDate, focus: String? = null, day: String? = null): DayPlan {
        val s = ctx.store
        if (focus == null && day == null) storedPlan(s, date)?.let {
            return it.copy(items = Planner.arrange(program(s), it.items, ctx.settings.supersets, it.focus, it.lead == "pull"))
        }
        return weekPlans(ctx, date, focus, day)[date] ?: DayPlan(date.toString(), emptyList(), focus, day = day)
    }

    /**
     * Все тренировки недели этой даты — одним расчётом ([Planner.week]): кто когда и сколько — по правилу «каждая мышца
     * хотя бы раз в неделю, два — если успевает восстановиться». Сделанные тренировки и сохранённые планы учитываются как есть.
     * focus/day — выбор человека для этой даты (акцент, тип дня).
     */
    fun weekPlans(ctx: Ctx, date: LocalDate, focus: String? = null, day: String? = null): Map<LocalDate, DayPlan> {
        val s = ctx.store
        val p = program(s)
        val mon = date.with(DayOfWeek.MONDAY)
        val trained = trainedDays(ctx, mon, mon.plusDays(6))
        val thisWeek = mon == ctx.today.with(DayOfWeek.MONDAY)
        // тренировки недели, которые ещё впереди (сделанные — в истории); другая неделя — только эта дата
        val dates = ((if (thisWeek) week(ctx).plan.filter { it !in trained } else emptyList()) + date).distinct().sorted()
        val days = split(ctx)
        val focusSet = focus?.let { FOCUS[it]?.second } ?: emptySet()
        // акцент переносит день в тот тип сплита, где акцентных мышц больше всего («Ноги» в день «Верх» → «Низ»)
        fun overlap(d: DayType) = d.muscles.count { it in focusSet }
        val best = days.maxOf(::overlap)
        fun storedType(d: LocalDate) = storedPlan(s, d)?.day?.let { id -> days.firstOrNull { it.id == id } }
        val sessions = dates.map { d ->
            if (d == date && (focus != null || day != null)) {
                val type = days.firstOrNull { it.id == day }
                    ?: (storedType(d) ?: dayType(ctx, d)).takeIf { overlap(it) == best } ?: days.first { overlap(it) == best }
                Planner.Session(d, type, focus)
            } else {
                val stored = storedPlan(s, d)
                Planner.Session(d, storedType(d) ?: dayType(ctx, d), stored?.focus, stored?.items)
            }
        }
        // сделанное этой и прошлой недели: объём недели и восстановление
        val hist = sessions(s).filter { it.first >= mon.minusDays(7) && it.first < mon.plusDays(7) && it.first !in dates }
        val doneSets = HashMap<String, Double>()
        hist.filter { it.first >= mon }.forEach { (_, sets) -> sets.filter { it.r >= 5 }.forEach { doneSets[it.ex] = (doneSets[it.ex] ?: 0.0) + 1 } }
        val composed = Planner.week(p, sessions, Planner.muscleLog(p, hist), doneSets, lastUsed(s), Planner.budget(ctx.settings.sessionMin))

        val out = LinkedHashMap<LocalDate, DayPlan>()
        for (ss in sessions) {
            val pl = composed[ss.date] ?: storedPlan(s, ss.date) ?: continue
            // в днях с грудью и спиной жимы и тяги меняются местами через раз: прошлый такой же день начинался с жимов → этот с тяг
            val lead = if (ss.fixed != null) pl.lead
                else if (ss.type?.muscles?.containsAll(listOf("chest", "back")) == true) {
                    val prev = (1L..21L).map { ss.date.minusDays(it) }.firstNotNullOfOrNull { d -> (out[d] ?: storedPlan(s, d))?.takeIf { it.day == ss.type.id } }
                    if (prev != null && prev.lead != "pull") "pull" else null
                } else null
            out[ss.date] = pl.copy(items = Planner.arrange(p, pl.items, ctx.settings.supersets, pl.focus, lead == "pull"), lead = lead)
        }
        return out
    }

    /** Цели по упражнениям плана (двойная прогрессия; число подходов — из плана). */
    fun targets(s: Store, plan: DayPlan, before: Long = Long.MAX_VALUE): List<Target> {
        val p = program(s)
        val date = LocalDate.parse(plan.date)
        return plan.items.mapNotNull { i -> p.ex(i.ex)?.copy(sets = i.sets) }.map { ex -> target(s, ex, date, before) }
    }

    /** Цель упражнения на дату: по тренировкам до [before] и перерыву с последней из них. */
    fun target(s: Store, ex: Exercise, date: LocalDate, before: Long = Long.MAX_VALUE): Target {
        val h = history(s, ex.id, before)
        return nextTarget(ex, h.map { it.second }, h.lastOrNull()?.let { ChronoUnit.DAYS.between(it.first, date) } ?: 0)
    }

    /** Текущая сила по упражнению: последний рабочий вес × повторы (или стартовые из базы). */
    fun strength(s: Store, ex: Exercise): String = strengthOf(ex, history(s, ex.id).lastOrNull()?.second)

    /** Сила по подходам последней тренировки: «60 кг × 8,8,7», «свой вес × 10,9», «15,14 повт.». */
    fun strengthOf(ex: Exercise, last: List<SetLog>?): String {
        val work = last?.let(::workingSets).orEmpty()
        if (work.isEmpty()) return ex.startWeight?.let { w -> loadText(ex, w)?.let { "$it × ${ex.repMin}" } ?: "${ex.repMin} повт." } ?: "вес не задан"
        val reps = work.joinToString(",") { it.r.toString() }
        return loadText(ex, work.first().w)?.let { "$it × $reps" } ?: "$reps повт."
    }

    /** Вывод по упражнению за последние [days] дней: растёт / медленно / стоит / падает (см. [verdict]). */
    fun trend(s: Store, ex: Exercise, to: LocalDate, days: Long = TREND_DAYS): ExTrend =
        trendOf(ex, strengthPoints(ex, history(s, ex.id), latestWeight(s) ?: 70.0).second, to, days,
            planPace(by.zaberezh.forma.core.SETTINGS.get(s).gymLevel))

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
        // понятным языком: лучший подход в начале и в конце периода, и вывод (сила считается по формуле Эпли)
        fun set(ex: Exercise, x: SetLog?) = x?.let { setText(ex, it) } ?: "—"
        trends.forEach { t ->
            val was = set(t.ex, t.firstSet); val now = set(t.ex, t.lastSet)
            lines += "${t.ex.name}: " + when {
                t.n <= 1 -> "$now · пока одна тренировка"
                t.status == "мало данных" -> "$was → $now · мало тренировок для вывода"
                else -> "$was → $now · ${if (t.status == "медленно") "растёт медленно" else t.status}" +
                    (t.pctWeek?.let { " (сила ${it.pct()} в неделю)" } ?: "")
            }
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
