package by.zaberezh.forma.core.gym

import by.zaberezh.forma.core.r1

/** Цель на следующую тренировку по упражнению (двойная прогрессия). */
data class Target(val ex: Exercise, val weight: Double?, val reps: List<Int>, val note: String) {
    fun short(): String = if (weight == null) "${ex.name}: подбор веса ${ex.repMin}–${ex.repMax}" else "${ex.name}: ${targetText(this)}"
}

/** Рабочие подходы сессии: подходы с максимальным весом. */
fun workingSets(sets: List<SetLog>): List<SetLog> {
    val max = sets.maxOfOrNull { it.w } ?: return emptyList()
    return sets.filter { it.w == max }
}

/** Перерыв в упражнении, после которого сначала повторяем прошлый результат (сила за 3+ недели немного уходит). */
const val LONG_BREAK_DAYS = 21L

/**
 * Двойная прогрессия: держим вес, пока во всех рабочих подходах не выполнен верх диапазона,
 * затем +шаг и возврат к низу диапазона. 3 тренировки на одном весе без прироста повторов → сброс −10%
 * (а если застрял у самого верха диапазона, недобирая повтор, — наоборот, +шаг: вес уже по силам).
 * - Число подходов от тренировки к тренировке бывает разным (его задаёт план недели), поэтому прирост
 *   сравнивается по повторам в среднем на подход, а не по сумме — иначе 2 подхода после 4 выглядят как застой.
 * - Упражнение без шага веса (планка, вис, подъём ног) растёт повторами и выше диапазона; сбрасывать там нечего.
 * - После перерыва [LONG_BREAK_DAYS]+ дней — сначала повторить прошлый результат.
 * @param history сессии по упражнению, от старых к новым (каждая — список подходов).
 * @param restDays дней с последней сессии по упражнению.
 */
fun nextTarget(ex: Exercise, history: List<List<SetLog>>, restDays: Long = 0): Target {
    val sessions = history.filter { it.isNotEmpty() }
    val last = sessions.lastOrNull()
        ?: return if (ex.startWeight != null) Target(ex, ex.startWeight, List(ex.sets) { ex.repMin }, "стартовый вес")
        else Target(ex, null, List(ex.sets) { ex.repMin }, "подбери вес на ${ex.repMin}–${ex.repMax} повт., в запасе ${ex.rir}")
    val work = workingSets(last)
    val w = work.first().w
    val loadable = ex.step > 0
    fun repeat() = List(ex.sets) { i -> (work.getOrNull(i) ?: work.last()).r.coerceAtLeast(1) }
    if (restDays >= LONG_BREAK_DAYS) return Target(ex, w, repeat(), "перерыв ${restDays} дн. — повтори прошлый результат, дальше снова вверх")

    // верх диапазона во всех рабочих подходах (хотя бы в двух, если сегодня их больше одного) → +шаг и снова с низа
    if (loadable && work.size >= minOf(ex.sets, 2) && work.take(ex.sets).all { it.r >= ex.repMax })
        return Target(ex, w + ex.step, List(ex.sets) { ex.repMin }, "+${ex.step.r1()} кг")

    val same = sessions.takeLast(3).map(::workingSets).filter { it.firstOrNull()?.w == w }
    if (same.size == 3) {
        val avg = same.map { s -> s.map { it.r }.average() }
        if (avg[2] <= avg[0] && avg[1] <= avg[0]) {
            // застрял у самого верха (недобирает повтор-другой) — вес уже по силам: +шаг, а не сброс вниз
            if (loadable && work.size >= minOf(ex.sets, 2) && avg[2] >= ex.repMax - maxOf(1.0, (ex.repMax - ex.repMin) * 0.2))
                return Target(ex, w + ex.step, List(ex.sets) { ex.repMin }, "3 раза у верха диапазона → +${ex.step.r1()} кг и снова с низа")
            val down = roundTo(w * 0.9, ex.step).let { if (it < w) it else w - ex.step }
            // с весом тела «0» — это свой вес, ниже не бывает; у обычного упражнения 0 кг — не тренировка
            if (loadable && down < w && (down > 0 || ex.bw > 0 && down >= 0))
                return Target(ex, down, List(ex.sets) { (ex.repMax - 2).coerceAtLeast(ex.repMin) }, "3 тренировки без прироста → сброс −10% и заново вверх")
            return Target(ex, w, repeat(), "3 тренировки без прироста — смени диапазон повторов или вариант упражнения")
        }
    }
    // +1 повтор; без шага веса — и выше диапазона, иначе расти некуда
    val cap = if (loadable) ex.repMax else 100
    val reps = List(ex.sets) { i -> ((work.getOrNull(i) ?: work.last()).r + 1).coerceAtMost(cap).coerceAtLeast(ex.repMin) }
    return Target(ex, w, reps, if (loadable) "+1 повтор в подходах, где возможно" else "без веса — прибавляй повторы")
}

/**
 * Цель с учётом очерёдности. Упражнение после другого на ту же мышцу (жим гантелей после жима лёжа) слабее, чем первым,
 * поэтому сравниваем с тренировками в той же очерёдности. Такая была среди последних 4 тренировок упражнения — обычная
 * двойная прогрессия по ним (и застой ищется тоже только среди них). Не было — от прошлого раза: сегодня после другого,
 * а тогда первым — на ~10% легче и прошлые повторы; сегодня первым, а тогда после — как обычно, сил будет больше.
 */
fun targetOf(ex: Exercise, h: List<ExSession>, after: Boolean, afterName: String? = null, restDays: Long = 0): Target {
    val what = afterName?.let { "после «$it»" } ?: "после другого упражнения на эту мышцу"
    val sameAt = h.indexOfLast { it.after == after }
    if (sameAt >= 0 && h.size - sameAt <= 4) {
        val t = nextTarget(ex, h.filter { it.after == after }.map { it.sets }, restDays)
        return if (after && t.weight != null) t.copy(note = "${t.note} · $what") else t
    }
    val t = nextTarget(ex, h.map { it.sets }, restDays)
    val last = h.lastOrNull() ?: return t
    if (after && !last.after) {
        val work = workingSets(last.sets)
        val w = work.first().w
        val reps = List(ex.sets) { i -> (work.getOrNull(i) ?: work.last()).r.coerceAtLeast(1) }
        val down = lighter(ex, w)
        return if (down < w) Target(ex, down, reps, "$what — ~10% легче, чем когда оно первое; дальше вверх как обычно")
        else Target(ex, w, reps.map { (it - 2).coerceAtLeast(1) }, "$what — повторов меньше, чем когда оно первое")
    }
    if (!after && last.after) return t.copy(note = "сегодня первым на эту мышцу — сил больше, чем в прошлый раз (тогда шло после другого)")
    return t
}

/** Вес на ~10% легче, кратно шагу; ниже нуля (или до нуля у обычного упражнения) — не опускаем. */
fun lighter(ex: Exercise, w: Double): Double {
    if (ex.step <= 0 || w <= 0) return w
    val down = roundTo(w * 0.9, ex.step).let { if (it < w) it else w - ex.step }
    return if (down > 0 || ex.bw > 0 && down >= 0) down else w
}

fun roundTo(v: Double, step: Double): Double = if (step <= 0) v else Math.round(v / step) * step
