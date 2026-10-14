package by.zaberezh.forma.core.gym

import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.body.latestWeight
import by.zaberezh.forma.core.body.weightGoal
import by.zaberezh.forma.core.body.weightRate
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.slope
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.roundToInt

/** Окно вывода «растёт / стоит»: последние 6 недель — одно и то же в базе упражнений, чекапе и «Прогрессе». */
const val TREND_DAYS = 42L

/** Стаж в зале → плановый рост силы, % в неделю: новичок прибавляет быстро, с опытом темп падает в разы. */
val LEVELS = listOf("до года", "1–3 года", "3+ года")
private val PACE = listOf(1.0, 0.5, 0.25)
fun planPace(level: Int): Double = PACE[level.coerceIn(0, PACE.lastIndex)]

val GOING = setOf("растёт", "медленно")
val STUCK = setOf("стоит", "падает")

/**
 * Сила в тренировке: лучший подход и он же, пересчитанный в разовый максимум (у упражнения без веса — повторы).
 * after — упражнение шло после другого на ту же мышцу (вес там ниже — с «первыми» тренировками не сравниваем).
 */
data class StrengthPoint(val date: LocalDate, val value: Double, val best: SetLog, val sets: List<SetLog>, val after: Boolean = false)

/** Меньше этого между первой и последней тренировкой — вывода нет: три тренировки за неделю — ещё не темп. */
const val MIN_SPAN_DAYS = 14L

/**
 * Точки силы по тренировкам: одна на день, от старых к новым. Сила — разовый максимум по Эпли с долей веса тела;
 * у упражнения совсем без веса (подъём ног в висе, планка) — повторы лучшего подхода, тогда first = true.
 */
fun strengthPoints(ex: Exercise, hist: List<ExSession>, bwKg: Double): Pair<Boolean, List<StrengthPoint>> {
    val bw = bwKg * ex.bw
    val loaded = hist.any { h -> h.sets.any { it.w + bw > 0 } }
    fun value(x: SetLog) = if (loaded) e1rm(x.w, x.r, bw) else x.r.toDouble()
    val pts = hist.groupBy { it.date }.map { (d, l) ->
        val sets = l.flatMap { it.sets }
        val best = sets.maxBy(::value)
        StrengthPoint(d, value(best), best, sets, l.first().after)
    }.filter { it.value > 0 }.sortedBy { it.date }
    return !loaded to pts
}

/**
 * Точки, которые можно сравнивать между собой. Если в последние [days] дней упражнение шло то первым на мышцу,
 * то после другого (жим гантелей после жима лёжа — вес ниже, и это не падение силы), сравниваем тренировки одной
 * очерёдности — той, что чаще (поровну — «первым»). Очерёдность всегда одна — все точки.
 */
fun comparable(all: List<StrengthPoint>, to: LocalDate, days: Long = TREND_DAYS): List<StrengthPoint> {
    val win = all.filter { it.date > to.minusDays(days) && it.date <= to }
    if (win.none { it.after } || win.all { it.after }) return all
    val after = win.count { it.after } > win.count { !it.after }
    return all.filter { it.after == after }
}

/**
 * % силы в неделю (регрессия по тренировкам) и вывод относительно планового темпа [plan]: растёт — не медленнее плана,
 * медленно — хотя бы треть плана, стоит — около нуля, падает — от −1%. Меньше 3 тренировок — «мало данных».
 */
fun verdict(pts: List<StrengthPoint>, to: LocalDate, plan: Double = planPace(0)): Pair<Double?, String> {
    if (pts.isEmpty()) return null to "нет данных"
    val mean = pts.map { it.value }.average()
    val pct = slope(pts.map { ChronoUnit.DAYS.between(to, it.date).toDouble() to it.value })
        ?.takeIf { mean > 0 }?.let { it * 7 / mean * 100 }
    return pct to when {
        pts.size < 3 || pct == null || ChronoUnit.DAYS.between(pts.first().date, pts.last().date) < MIN_SPAN_DAYS -> "мало данных"
        pct >= plan - 1e-9 -> "растёт"
        pct >= plan * 0.3 -> "медленно"
        pct > -1.0 -> "стоит"
        else -> "падает"
    }
}

/** Вывод по упражнению за последние [days] дней до [to] по его точкам силы. */
fun trendOf(ex: Exercise, all: List<StrengthPoint>, to: LocalDate, days: Long = TREND_DAYS, plan: Double = planPace(0)): ExTrend {
    val pts = all.filter { it.date > to.minusDays(days) && it.date <= to }
    if (pts.isEmpty()) return ExTrend(ex, 0, 0.0, 0.0, 0.0, null, "нет данных")
    val (pct, status) = verdict(pts, to, plan)
    return ExTrend(ex, pts.size, pts.first().value, pts.last().value, pts.maxOf { it.value }, pct, status, pts.first().best, pts.last().best)
}

/** Вес понятным языком: «60 кг», «+10 кг» и «свой вес» (с весом тела); null — упражнение без веса. */
fun loadText(ex: Exercise, w: Double): String? = when {
    ex.bw > 0 -> if (w > 0) "+${w.r1()} кг" else "свой вес"
    w > 0 -> "${w.r1()} кг"
    else -> null
}

/** Подход понятным языком: «60 кг × 8», «+10 кг × 6» и «свой вес × 12» (с весом тела), «15 повт.» (без веса). */
fun setText(ex: Exercise, x: SetLog): String = loadText(ex, x.w)?.let { "$it × ${x.r}" } ?: "${x.r} повт."

/**
 * Прогресс упражнения. points — тренировки выбранного периода (график, «было → стало»), trend — вывод
 * по последним 6 неделям, next — цель следующей тренировки (двойная прогрессия), advice — почему может не идти.
 */
data class ExProgress(
    val ex: Exercise, val points: List<StrengthPoint>, val reps: Boolean, val trend: ExTrend,
    val next: Target, val advice: List<String>,
    val plan: Double = planPace(0),          // плановый рост силы, % в неделю
    val planFrom: StrengthPoint? = null,     // откуда идёт плановая линия: первая тренировка окна вывода (6 недель)
    val bwLoad: Double = 0.0,                // вес тела в нагрузке, кг (подтягивания, брусья)
    val line: List<StrengthPoint> = points,  // сравнимые тренировки периода — одной очерёдности (см. [comparable])
    val from: LocalDate? = null,             // «начать отсчёт заново» с этого дня (сменил тренажёр или технику)
) {
    val last: LocalDate get() = points.last().date
    /** Очерёдность менялась: часть тренировок — после другого упражнения на эту мышцу, их сравниваем отдельно. */
    val mixed: Boolean get() = line.size < points.size
    /** Сравниваются тренировки, где упражнение шло после другого (иначе — первым). */
    val comparedAfter: Boolean get() = line.firstOrNull()?.after == true
    /** Изменение силы за период — от первой сравнимой тренировки к последней, %. */
    val change: Double? get() = if (line.size < 2) null else (line.last().value / line.first().value - 1) * 100

    /** Сила по плану на дату: от первой тренировки окна плановым темпом. */
    fun planned(d: LocalDate): Double? = planFrom?.let { it.value * (1 + plan / 100).pow(ChronoUnit.DAYS.between(it.date, d) / 7.0) }

    /** Насколько последняя тренировка выше (+) или ниже (−) плана, %; нужна хотя бы неделя от начала плановой линии. */
    val vsPlan: Double? get() {
        val start = planFrom ?: return null
        val end = line.lastOrNull() ?: return null
        if (ChronoUnit.DAYS.between(start.date, end.date) < 7) return null
        return planned(end.date)?.let { (end.value / it - 1) * 100 }
    }

    /**
     * Цель через [weeks] недель по плану — понятным подходом: сначала повторы на текущем весе, не влезают в диапазон —
     * +шаг веса. «62.5 кг × 11», «65 кг × 8», «свой вес × 12», «16 повт.».
     */
    fun planGoal(weeks: Int): String {
        val now = line.lastOrNull() ?: points.last()
        val v = now.value * (1 + plan / 100).pow(weeks.toDouble())
        if (reps) return "${v.roundToInt().coerceAtLeast(now.best.r)} повт."
        var w = now.best.w
        repeat(40) {
            val r = ((v / (w + bwLoad) - 1) * 30).roundToInt()
            // на том же весе — не меньше повторов, чем сейчас; на новом — от низа диапазона, как в двойной прогрессии
            val floor = if (w == now.best.w) now.best.r else ex.repMin
            if (r <= ex.repMax || ex.step <= 0) return setText(ex, SetLog(ex.id, w, r.coerceAtMost(30).coerceAtLeast(floor)))
            w += ex.step
        }
        return setText(ex, now.best)
    }
}

/**
 * Прогресс по упражнениям, которые делал за период. going / stuck — главные мышцы, где хоть одно упражнение растёт /
 * где все упражнения с выводом стоят или падают. notes — общее: застой почти везде (дело не в упражнениях), дефицит.
 */
data class GymProgress(val items: List<ExProgress>, val going: List<String>, val stuck: List<String>, val notes: List<String>)

/**
 * Прогресс силы с [from] по сегодня (null — за всё время). Вывод «растёт / стоит» — всегда по последним
 * [TREND_DAYS] дням, чтобы не зависел от масштаба графика: за полгода могло расти, а последний месяц — стоять.
 */
fun GymModule.progress(ctx: Ctx, from: LocalDate? = null): GymProgress {
    val s = ctx.store
    val to = ctx.today
    val p = program(s)
    val bwKg = latestWeight(s) ?: 70.0
    val plan = planPace(ctx.settings.gymLevel)
    class Raw(val ex: Exercise, val reps: Boolean, val all: List<StrengthPoint>, val pts: List<StrengthPoint>, val hist: List<ExSession>, val base: LocalDate?) {
        val cmp = comparable(all, to)                 // одной очерёдности — их и сравниваем
        val trend = trendOf(ex, cmp, to, plan = plan)
        val line = cmp.filter { it.date >= pts.first().date }
    }
    val raw = p.exercises.mapNotNull { ex ->
        val hist = progressSessions(s, ex.id).filter { it.date <= to }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val (reps, all) = strengthPoints(ex, hist, bwKg)
        val pts = all.filter { from == null || it.date >= from }
        if (pts.isEmpty()) null else Raw(ex, reps, all, pts, hist, progressFrom(s, ex.id))
    }
    val judged = raw.filter { it.trend.status in GOING + STUCK }
    val bad = judged.filter { it.trend.status in STUCK }
    val systemic = judged.size >= 3 && bad.size * 2 >= judged.size
    val notes = mutableListOf<String>()
    if (systemic) notes += "Не идёт в ${bad.size} из ${judged.size} упражнений — значит, дело не в упражнениях, а в восстановлении. " +
        "Сначала сон и еда (белок ${ctx.settings.profile.proteinPerKg.r1()} г на кг веса, калории по плану); " +
        "если с ними порядок — разгрузочная неделя: те же веса, −40% подходов, потом обычно снова растёт."
    val cutting = weightGoal(s, ctx.settings.profile, to).kind == "похудение" || (weightRate(s, to) ?: 0.0) <= -0.3
    if (cutting && raw.any { it.trend.status in STUCK || it.trend.status == "медленно" })
        notes += "Ты худеешь: на дефиците сила растёт медленнее плана, и удержать её — уже хорошо."

    // подходов на мышцу в неделю за последние 4 недели — проверка «мало объёма» для того, что стоит; начал ходить
    // позже — делим на недели, когда уже ходил (иначе первая неделя в приложении выглядит как «мало подходов»)
    val weeks = (firstDay(s)?.takeIf { it > to.minusDays(27) }?.let { (ChronoUnit.DAYS.between(it, to) + 1) / 7.0 } ?: 4.0).coerceIn(1.0, 4.0)
    val weekly = volume(s, to.minusDays(27), to).mapValues { it.value / weeks }
    val ranges = DEFAULT_VOLUME + p.volume
    val items = raw.map { r ->
        val six = r.cmp.filter { it.date > to.minusDays(TREND_DAYS) }
        val after = r.line.firstOrNull()?.after ?: false
        ExProgress(r.ex, r.pts, r.reps, r.trend, targetOf(r.ex, exSessions(s, r.ex.id), after, null, ChronoUnit.DAYS.between(r.all.last().date, to)),
            advice(r.ex, r.trend, six, weekly, ranges, systemic),
            plan, six.firstOrNull(), bwKg * r.ex.bw, r.line.ifEmpty { r.pts }, r.base)
    }
    val byMuscle = judged.groupBy { mainMuscle(it.ex) }
    val going = MUSCLES.keys.filter { m -> byMuscle[m]?.any { it.trend.status in GOING } == true }
    val stuck = MUSCLES.keys.filter { m -> byMuscle[m]?.all { it.trend.status in STUCK } == true }
    return GymProgress(items, going, stuck, notes)
}

/** Почему упражнение стоит или падает и что попробовать — по данным, а не общими словами, где получается. */
private fun advice(
    ex: Exercise, t: ExTrend, six: List<StrengthPoint>, weekly: Map<String, Double>, ranges: Map<String, List<Int>>, systemic: Boolean,
): List<String> {
    if (t.status !in STUCK) return emptyList()
    val out = mutableListOf<String>()
    // редко: сила в конкретном упражнении растёт, когда оно есть хотя бы раз в неделю
    if (six.size >= 2) {
        val gap = ChronoUnit.DAYS.between(six.first().date, six.last().date) / (six.size - 1).toDouble()
        if (gap >= 10) out += "Делаешь его раз в ~${gap.roundToInt()} дн. — для роста в упражнении нужно хотя бы раз в неделю."
    }
    val m = mainMuscle(ex)
    val range = ranges[m]
    val v = weekly[m] ?: 0.0
    if (range != null && range[0] > 0 && v < range[0])
        out += "Мало подходов: ${MUSCLES[m]?.substringBefore(" (") ?: m} — ${v.r1()} в неделю при норме ${range[0]}–${range[1]}."
    // при общем застое общий совет — один, наверху; здесь — только своё
    if (!systemic) out += if (t.status == "падает")
        "Сила падает: чаще всего это недосып, мало еды или накопленная усталость. Если со сном и едой порядок — разгрузочная неделя: те же веса, −40% подходов."
    else "Проверь, что подходы тяжёлые — в запасе не больше ${ex.rir} повт. Не сдвинется за 2–3 недели — смени диапазон на ${otherRange(ex)} повт. " +
        "или вариант упражнения на 4–6 недель."
    return out
}

/** Другой диапазон повторов для выхода из застоя: тяжёлым — полегче, лёгким — потяжелее. */
private fun otherRange(ex: Exercise): String = when {
    ex.repMax <= 10 -> "10–15"
    ex.repMin >= 12 -> "8–12"
    else -> "6–10"
}

/** Цель следующей тренировки понятным языком: «65 кг × 9, 9, 8», «свой вес × 10, 10, 9», «12, 12, 12 повт.». */
fun targetText(t: Target): String {
    val w = t.weight ?: return "подбери вес на ${t.ex.repMin}–${t.ex.repMax} повт."
    val reps = t.reps.joinToString(", ")
    return loadText(t.ex, w)?.let { "$it × $reps" } ?: "$reps повт."
}
