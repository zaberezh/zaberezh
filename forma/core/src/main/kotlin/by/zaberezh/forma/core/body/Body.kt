package by.zaberezh.forma.core.body

import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.Field
import by.zaberezh.forma.core.Module
import by.zaberezh.forma.core.Profile
import by.zaberezh.forma.core.Section
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.r2
import by.zaberezh.forma.core.slope
import by.zaberezh.forma.core.store.Kind
import by.zaberezh.forma.core.store.Store
import by.zaberezh.forma.core.store.endMs
import by.zaberezh.forma.core.store.startMs
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.log10

@Serializable data class Weight(val kg: Double)
@Serializable data class Measure(val v: Map<String, Double>)
@Serializable data class Photo(val path: String, val pose: String)

val WEIGHT = Kind("body.weight", Weight.serializer())
val MEASURE = Kind("body.measure", Measure.serializer())
val PHOTO = Kind("body.photo", Photo.serializer())

/** Замеры: утром до еды и тренировки, одной лентой, лента плотно, но не впивается; 2 замера → среднее. */
val MEASURE_FIELDS = listOf(
    Field("waist", "Талия", "см", "По линии пупка, утром натощак, на спокойном выдохе. Живот не втягивать, лента строго горизонтально."),
    Field("chest", "Грудь", "см", "По самой выступающей точке груди спереди и под лопатками сзади. Руки опущены, спокойный выдох."),
    Field("arm", "Бицепс", "см", "Правая рука: согни и напряги (кулак к плечу, локоть на уровне плеча), по самой высокой точке бицепса."),
    Field("forearm", "Предплечье", "см", "Правая рука выпрямлена, кулак сжат, в самой широкой части — примерно 5 см ниже локтя."),
)
val POSES = listOf("front" to "спереди", "side" to "сбоку", "back" to "сзади")

/** Вес за день — одна запись: повторное сохранение в тот же день заменяет её (и чистит старые дубли). */
fun saveWeight(s: Store, day: LocalDate, kg: Double, now: Long = System.currentTimeMillis()) {
    WEIGHT.all(s, day.startMs(), day.endMs()).forEach { s.delete(it.first.id) }
    val ts = now.coerceIn(day.startMs(), day.endMs())
    WEIGHT.save(s, Weight(kg), ts = ts, id = "weight:$day")
}

fun weightOn(s: Store, day: LocalDate): Double? = WEIGHT.all(s, day.startMs(), day.endMs()).lastOrNull()?.second?.kg

/** Последние дни с весом: по одной записи на день (последняя за день), новые первыми. */
fun weightDays(s: Store, n: Int): List<Pair<LocalDate, Double>> =
    WEIGHT.all(s).groupBy { it.first.day }.map { (d, l) -> d to l.last().second.kg }.sortedByDescending { it.first }.take(n)

fun latestWeight(s: Store): Double? = WEIGHT.all(s).lastOrNull()?.second?.kg

/** Дневные веса (среднее за день), по возрастанию. */
fun dailyWeights(s: Store, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Double>> =
    WEIGHT.all(s, from.startMs(), to.endMs()).groupBy { it.first.day }
        .map { (d, l) -> d to l.map { it.second.kg }.average() }.sortedBy { it.first }

/** Темп изменения веса, кг/нед (регрессия по сырым весам). */
fun weightRate(s: Store, to: LocalDate, days: Long = 21): Double? {
    val w = dailyWeights(s, to.minusDays(days - 1), to)
    if (w.size < 5 || ChronoUnit.DAYS.between(w.first().first, w.last().first) < 10) return null
    return slope(w.map { ChronoUnit.DAYS.between(to, it.first).toDouble() to it.second })?.times(7)
}

/** Сглаженный вес (EMA, α=0.1 по дням) — убирает шум воды/соли. */
fun trendWeight(s: Store, to: LocalDate): Double? {
    val w = dailyWeights(s, to.minusDays(60), to)
    if (w.isEmpty()) return null
    var ema = w.first().second
    w.drop(1).forEach { ema += 0.1 * (it.second - ema) }
    return ema
}

/** Темп набора к целевому весу, кг/нед: медленный, чтобы росли мышцы, а не жир. */
const val GAIN_PACE = 0.25
/** Темп похудения к целевому весу, кг/нед: сила и мышцы сохраняются. */
const val LOSS_PACE = 0.5
/** Ближе этого к цели — держим вес. */
const val HOLD_KG = 0.5

/**
 * Цель по весу: старт → цель. Темп не задаётся руками — он следует из направления: набор +[GAIN_PACE],
 * похудение −[LOSS_PACE] кг/нед; rate нужен только для калорий. Показываются прогресс, дата и «по плану ли».
 */
data class WeightGoal(
    val rate: Double,
    val targetKg: Double? = null,
    val current: Double? = null,
    val startKg: Double? = null,
    val startDay: LocalDate? = null,
) {
    val kind: String get() = when { targetKg == null -> "держим вес"; rate > 0 -> "набор"; rate < 0 -> "похудение"; else -> "держим вес" }
    /** Сколько недель до цели; null — цели нет или уже у цели. */
    val weeks: Int? get() = if (targetKg == null || current == null || rate == 0.0) null
        else kotlin.math.ceil((targetKg - current) / rate).toInt().coerceAtLeast(1)
    /** Весь путь, кг. */
    val totalKg: Double? get() = if (startKg != null && targetKg != null) kotlin.math.abs(targetKg - startKg) else null
    /** Пройдено к цели от старта, кг (в обратную сторону — минус). */
    val doneKg: Double? get() {
        val s = startKg ?: return null; val t = targetKg ?: return null; val c = current ?: return null
        return if (t >= s) c - s else s - c
    }
    val progress: Double? get() {
        val tot = totalKg ?: return null; val d = doneKg ?: return null
        return if (tot < 0.05) 1.0 else (d / tot).coerceIn(0.0, 1.0)
    }
    /** Где вес должен быть в этот день по плану: от старта в сторону цели обычным темпом, не дальше цели. */
    fun planned(day: LocalDate): Double? {
        val s = startKg ?: return null; val t = targetKg ?: return null; val d0 = startDay ?: return null
        val w = ChronoUnit.DAYS.between(d0, day).coerceAtLeast(0) / 7.0
        return when {
            t > s -> minOf(s + GAIN_PACE * w, t)
            t < s -> maxOf(s - LOSS_PACE * w, t)
            else -> s
        }
    }
    /** Насколько впереди плана, кг: плюс — ближе к цели, чем по плану; минус — отстаёшь. */
    fun vsPlan(day: LocalDate): Double? {
        val p = planned(day) ?: return null; val c = current ?: return null; val t = targetKg ?: return null; val s = startKg ?: return null
        return if (t >= s) c - p else p - c
    }
    val label: String get() = when {
        targetKg == null -> "не задана — держим вес"
        startKg != null -> "${startKg.r1()} → ${targetKg.r1()} кг · $kind"
        else -> "${targetKg.r1()} кг · $kind"
    }
}

/** Цель задана — набор или похудение по текущему (сглаженному) весу; нет цели — держим вес. */
fun weightGoal(p: by.zaberezh.forma.core.Profile, current: Double?): WeightGoal {
    val t = p.targetKg ?: return WeightGoal(0.0, current = current)
    val start = p.startKg ?: current
    val day = p.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (current == null) return WeightGoal(if (start != null && t > start) GAIN_PACE else if (start != null && t < start) -LOSS_PACE else 0.0, t, null, start, day)
    val diff = t - current
    val rate = when {
        kotlin.math.abs(diff) < HOLD_KG -> 0.0
        diff > 0 -> GAIN_PACE
        else -> -LOSS_PACE
    }
    return WeightGoal(rate, t, current, start, day)
}

/**
 * Новая цель. Старт — как указал человек, иначе текущий вес; «по плану» считается с сегодня,
 * но если старт тот же, что был, — дата старта остаётся (поменял только цель — путь не начинается заново).
 */
fun Profile.withGoal(target: Double?, start: Double?, current: Double?, today: LocalDate): Profile {
    if (target == null) return copy(targetKg = null, startKg = null, startDate = null)
    val st = start ?: current
    val keepDay = st != null && startKg != null && kotlin.math.abs(st - startKg) < 0.05 && startDate != null
    return copy(targetKg = target, startKg = st, startDate = if (keepDay) startDate else today.toString())
}

fun weightGoal(s: Store, p: by.zaberezh.forma.core.Profile, day: LocalDate): WeightGoal =
    weightGoal(p, trendWeight(s, day) ?: latestWeight(s))

/** Как дела относительно плана: v — насколько впереди (плюс) или позади (минус), кг. */
fun planNote(v: Double): String = when {
    v >= 0.7 -> "быстрее плана на ${v.r1()} кг"
    v <= -0.7 -> "отстаёшь от плана на ${(-v).r1()} кг"
    else -> "идёшь по плану"
}

/** Ближайшее время напоминания взвеситься: будни и выходные — своё время. */
fun nextWeighTime(st: by.zaberezh.forma.core.Settings, now: java.time.LocalDateTime): java.time.LocalDateTime {
    for (i in 0L..7L) {
        val d = now.toLocalDate().plusDays(i)
        val weekend = d.dayOfWeek.value >= 6
        val t = if (weekend) d.atTime(st.weighWeekendHour, st.weighWeekendMinute) else d.atTime(st.weighHour, st.weighMinute)
        if (t.isAfter(now)) return t
    }
    return now.plusDays(1)
}

/** % жира по формуле ВМС США (мужчины): талия, шея, рост в см. */
fun navyBodyFat(waist: Double, neck: Double, height: Double): Double? =
    if (neck <= 0 || waist <= neck) null else 495 / (1.0324 - 0.19077 * log10(waist - neck) + 0.15456 * log10(height)) - 450

object BodyModule : Module {
    override val id = "body"
    override val title = "Тело"

    fun lastMeasure(s: Store) = MEASURE.all(s).lastOrNull()

    override fun morning(ctx: Ctx): List<String> {
        val out = mutableListOf<String>()
        val s = ctx.store
        val lm = lastMeasure(s)?.first?.day
        val days = lm?.let { ChronoUnit.DAYS.between(it, ctx.today) }
        if (days == null || days >= 28) out += "Замеры: " + (days?.let { "прошло $it дн." } ?: "ещё не делались")
        return out
    }

    override fun checkup(ctx: Ctx, from: LocalDate, to: LocalDate): Section {
        val s = ctx.store
        val lines = mutableListOf<String>()
        val actions = mutableListOf<String>()
        val w = dailyWeights(s, from, to)
        val rate = weightRate(s, to, ChronoUnit.DAYS.between(from, to) + 1)
        val goal = weightGoal(s, ctx.settings.profile, to)
        val target = goal.rate
        goal.targetKg?.let {
            lines += "Цель: ${goal.label}"
            val done = goal.doneKg; val tot = goal.totalKg
            if (done != null && tot != null) lines += "Пройдено: ${done.r1()} из ${tot.r1()} кг (${((goal.progress ?: 0.0) * 100).toInt()}%)"
            goal.planned(to)?.let { p -> lines += "По плану сейчас ${p.r1()} кг" + (goal.vsPlan(to)?.let { v -> " — " + planNote(v) } ?: "") }
        }
        if (w.isNotEmpty()) lines += "Вес: ${w.first().second.r1()} → ${w.last().second.r1()} кг, взвешиваний ${w.size}"
        trendWeight(s, to)?.let { lines += "Сглаженный вес: ${it.r1()} кг" }
        rate?.let { lines += "Фактический темп: ${it.r2()} кг/нед (нужно ${target.r2()})" }
        if (w.size < 7) actions += "Взвешиваний мало (${w.size}) — нужен минимум 4 раза в неделю для точного темпа."

        val ms = MEASURE.all(s).map { it.first.day to it.second.v }
        val cur = ms.lastOrNull { it.first <= to }
        val prev = ms.lastOrNull { cur != null && it.first < cur.first && it.first <= from } ?: ms.firstOrNull { cur != null && it.first < cur.first }
        if (cur != null) {
            MEASURE_FIELDS.forEach { f ->
                val a = prev?.second?.get(f.key); val b = cur.second[f.key]
                if (b != null) lines += "${f.label}: " + (a?.let { "${it.r1()} → " } ?: "") + "${b.r1()} см"
            }
            val h = ctx.settings.profile.heightCm
            val bf = navyBodyFat(cur.second["waist"] ?: 0.0, cur.second["neck"] ?: 0.0, h)
            val bf0 = prev?.let { navyBodyFat(it.second["waist"] ?: 0.0, it.second["neck"] ?: 0.0, h) }
            bf?.let { lines += "% жира (формула ВМС, ±3%): " + (bf0?.let { "${it.r1()} → " } ?: "") + it.r1() }
            val dw = (cur.second["waist"] ?: 0.0) - (prev?.second?.get("waist") ?: cur.second["waist"] ?: 0.0)
            if (prev != null && dw >= 1.0) actions += "Талия +${dw.r1()} см — набор идёт с жиром, срежь 150 ккал."
        }
        return Section(title, lines, actions, mapOf("weights" to w.size, "rate_kg_week" to rate, "target_rate" to target, "target_kg" to goal.targetKg,
            "measure_last" to cur?.second, "measure_prev" to prev?.second))
    }
}
