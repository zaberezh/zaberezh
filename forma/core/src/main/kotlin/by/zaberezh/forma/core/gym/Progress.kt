package by.zaberezh.forma.core.gym

import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.body.latestWeight
import by.zaberezh.forma.core.body.weightGoal
import by.zaberezh.forma.core.body.weightRate
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.slope
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Окно вывода «растёт / стоит»: последние 6 недель — одно и то же в базе упражнений, чекапе и «Прогрессе». */
const val TREND_DAYS = 42L

val GOING = setOf("растёт", "медленно")
val STUCK = setOf("стоит", "падает")

/** Сила в тренировке: лучший подход и он же, пересчитанный в разовый максимум (у упражнения без веса — повторы). */
data class StrengthPoint(val date: LocalDate, val value: Double, val best: SetLog, val sets: List<SetLog>)

/**
 * Точки силы по тренировкам: одна на день, от старых к новым. Сила — разовый максимум по Эпли с долей веса тела;
 * у упражнения совсем без веса (подъём ног в висе, планка) — повторы лучшего подхода, тогда first = true.
 */
fun strengthPoints(ex: Exercise, hist: List<Pair<LocalDate, List<SetLog>>>, bwKg: Double): Pair<Boolean, List<StrengthPoint>> {
    val bw = bwKg * ex.bw
    val loaded = hist.any { (_, sets) -> sets.any { it.w + bw > 0 } }
    fun value(x: SetLog) = if (loaded) e1rm(x.w, x.r, bw) else x.r.toDouble()
    val pts = hist.groupBy({ it.first }, { it.second }).map { (d, l) ->
        val sets = l.flatten()
        val best = sets.maxBy(::value)
        StrengthPoint(d, value(best), best, sets)
    }.filter { it.value > 0 }.sortedBy { it.date }
    return !loaded to pts
}

/** % силы в неделю (регрессия по тренировкам) и вывод; меньше 3 тренировок — «мало данных». */
fun verdict(pts: List<StrengthPoint>, to: LocalDate): Pair<Double?, String> {
    if (pts.isEmpty()) return null to "нет данных"
    val mean = pts.map { it.value }.average()
    val pct = slope(pts.map { ChronoUnit.DAYS.between(to, it.date).toDouble() to it.value })
        ?.takeIf { mean > 0 }?.let { it * 7 / mean * 100 }
    return pct to when {
        pts.size < 3 || pct == null -> "мало данных"
        pct >= 1.0 -> "растёт"
        pct >= 0.3 -> "медленно"
        pct > -1.0 -> "стоит"
        else -> "падает"
    }
}

/** Вывод по упражнению за последние [days] дней до [to] по его точкам силы. */
fun trendOf(ex: Exercise, all: List<StrengthPoint>, to: LocalDate, days: Long = TREND_DAYS): ExTrend {
    val pts = all.filter { it.date > to.minusDays(days) && it.date <= to }
    if (pts.isEmpty()) return ExTrend(ex, 0, 0.0, 0.0, 0.0, null, "нет данных")
    val (pct, status) = verdict(pts, to)
    return ExTrend(ex, pts.size, pts.first().value, pts.last().value, pts.maxOf { it.value }, pct, status, pts.first().best, pts.last().best)
}

/** Подход понятным языком: «60 кг × 8», «+10 кг × 6» и «свой вес × 12» (с весом тела), «15 повт.» (без веса). */
fun setText(ex: Exercise, x: SetLog): String = when {
    ex.bw > 0 -> (if (x.w > 0) "+${x.w.r1()} кг" else "свой вес") + " × ${x.r}"
    x.w > 0 -> "${x.w.r1()} кг × ${x.r}"
    else -> "${x.r} повт."
}

/**
 * Прогресс упражнения. points — тренировки выбранного периода (график, «было → стало»), trend — вывод
 * по последним 6 неделям, next — цель следующей тренировки (двойная прогрессия), advice — почему может не идти.
 */
data class ExProgress(
    val ex: Exercise, val points: List<StrengthPoint>, val reps: Boolean, val trend: ExTrend,
    val next: Target, val advice: List<String>,
) {
    val last: LocalDate get() = points.last().date
    /** Изменение силы за период — от первой тренировки к последней, %. */
    val change: Double? get() = if (points.size < 2) null else (points.last().value / points.first().value - 1) * 100
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
    val byEx = HashMap<String, MutableList<Pair<LocalDate, List<SetLog>>>>()
    sessions(s).filter { it.first <= to }.forEach { (d, sets) ->
        sets.groupBy { it.ex }.forEach { (id, l) -> byEx.getOrPut(id) { mutableListOf() } += d to l }
    }
    class Raw(val ex: Exercise, val reps: Boolean, val all: List<StrengthPoint>, val pts: List<StrengthPoint>, val hist: List<List<SetLog>>) {
        val trend = trendOf(ex, all, to)
    }
    val raw = p.exercises.mapNotNull { ex ->
        val hist = byEx[ex.id] ?: return@mapNotNull null
        val (reps, all) = strengthPoints(ex, hist, bwKg)
        val pts = all.filter { from == null || it.date >= from }
        if (pts.isEmpty()) null else Raw(ex, reps, all, pts, hist.map { it.second })
    }
    val judged = raw.filter { it.trend.status in GOING + STUCK }
    val bad = judged.filter { it.trend.status in STUCK }
    val systemic = judged.size >= 3 && bad.size * 2 >= judged.size
    val notes = mutableListOf<String>()
    if (systemic) notes += "Не идёт в ${bad.size} из ${judged.size} упражнений — значит, дело не в упражнениях, а в восстановлении. " +
        "Сначала сон и еда (белок ${ctx.settings.profile.proteinPerKg.r1()} г на кг веса, калории по плану); " +
        "если с ними порядок — разгрузочная неделя: те же веса, −40% подходов, потом обычно снова растёт."
    val cutting = weightGoal(s, ctx.settings.profile, to).kind == "похудение" || (weightRate(s, to) ?: 0.0) <= -0.3
    if (cutting && bad.isNotEmpty()) notes += "Ты худеешь: на дефиците сила растёт медленно, и удержать её — уже хорошо."

    // подходов на мышцу в неделю за последние 4 недели — проверка «мало объёма» для того, что стоит
    val weekly = volume(s, to.minusDays(27), to).mapValues { it.value / 4 }
    val ranges = DEFAULT_VOLUME + p.volume
    val items = raw.map { r ->
        val six = r.all.filter { it.date > to.minusDays(TREND_DAYS) }
        ExProgress(r.ex, r.pts, r.reps, r.trend, nextTarget(r.ex, r.hist), advice(r.ex, r.trend, six, weekly, ranges, systemic))
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
    return when {
        t.ex.bw > 0 -> (if (w > 0) "+${w.r1()} кг" else "свой вес") + " × $reps"
        w > 0 -> "${w.r1()} кг × $reps"
        else -> "$reps повт."
    }
}
