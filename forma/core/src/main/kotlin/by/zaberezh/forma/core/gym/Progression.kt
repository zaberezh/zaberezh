package by.zaberezh.forma.core.gym

import by.zaberezh.forma.core.r1

/** Цель на следующую тренировку по упражнению (двойная прогрессия). */
data class Target(val ex: Exercise, val weight: Double?, val reps: List<Int>, val note: String) {
    fun short(): String = if (weight == null) "${ex.name}: подбор веса ${ex.repMin}–${ex.repMax}" else
        "${ex.name}: ${weight.r1()}×${reps.joinToString(",")}"
}

/** Рабочие подходы сессии: подходы с максимальным весом. */
fun workingSets(sets: List<SetLog>): List<SetLog> {
    val max = sets.maxOfOrNull { it.w } ?: return emptyList()
    return sets.filter { it.w == max }
}

/**
 * Двойная прогрессия: держим вес, пока во всех рабочих подходах не выполнен верх диапазона,
 * затем +шаг и возврат к низу диапазона. 3 сессии без прироста повторов на одном весе -> сброс −10%.
 * @param history сессии по упражнению, от старых к новым (каждая — список подходов).
 */
fun nextTarget(ex: Exercise, history: List<List<SetLog>>): Target {
    val sessions = history.filter { it.isNotEmpty() }
    val last = sessions.lastOrNull()
        ?: return Target(ex, null, List(ex.sets) { ex.repMin }, "подбери вес на ${ex.repMin}–${ex.repMax} повт., в запасе ${ex.rir}")
    val work = workingSets(last)
    val w = work.first().w
    if (work.size >= ex.sets && work.take(ex.sets).all { it.r >= ex.repMax } && ex.step > 0)
        return Target(ex, w + ex.step, List(ex.sets) { ex.repMin }, "+${ex.step.r1()} кг")

    val same = sessions.takeLast(3).map(::workingSets).filter { it.firstOrNull()?.w == w }
    if (same.size == 3) {
        val totals = same.map { s -> s.take(ex.sets).sumOf { it.r } }
        if (totals[2] <= totals[0] && totals[1] <= totals[0]) {
            val reset = roundTo(w * 0.9, ex.step)
            return Target(ex, reset, List(ex.sets) { ex.repMax - 2 }, "3 сессии без прироста → сброс −10% и заново вверх")
        }
    }
    val reps = List(ex.sets) { i -> ((work.getOrNull(i) ?: work.last()).r + 1).coerceAtMost(ex.repMax).coerceAtLeast(ex.repMin) }
    return Target(ex, w, reps, "+1 повтор в подходах, где возможно")
}

fun roundTo(v: Double, step: Double): Double = if (step <= 0) v else Math.round(v / step) * step
