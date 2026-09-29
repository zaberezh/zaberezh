package by.zaberezh.forma.core.gym

import java.time.DayOfWeek
import java.time.LocalDate

data class WeekPlan(
    val done: Int,                  // тренировок уже на этой неделе
    val plan: List<LocalDate>,      // рекомендуемые дни (включая сделанные)
    val todayGym: Boolean,          // сегодня в плане
    val canSkipToday: Boolean,      // можно перенести без потери цели
    val achievable: Int,            // максимум, который ещё можно набрать
)

/**
 * План недели: только будни, [target] тренировок, не больше [maxRun] дней подряд.
 * Минимизируем соседние дни (восстановление), при равенстве — раньше (запас на форс-мажор).
 */
fun planWeek(trained: Set<LocalDate>, today: LocalDate, target: Int = 3, maxRun: Int = 2): WeekPlan {
    val monday = today.with(DayOfWeek.MONDAY)
    val week = (0L..6L).map { monday.plusDays(it) }.toSet()
    val done = trained.filter { it in week }.toSet()
    val weekdays = (0L..4L).map { monday.plusDays(it) }
    val free = weekdays.filter { it >= today && it !in done }
    val need = (target - done.size).coerceAtLeast(0)

    fun valid(days: Set<LocalDate>): Boolean {
        var run = 0
        for (d in weekdays) { run = if (d in days) run + 1 else 0; if (run > maxRun) return false }
        return true
    }
    fun adj(days: Set<LocalDate>) = weekdays.zipWithNext().count { (a, b) -> a in days && b in days }

    var best: List<LocalDate>? = null
    var size = minOf(need, free.size)
    while (size >= 0 && best == null) {
        best = combos(free, size).filter { valid(done + it) }
            .minWithOrNull(compareBy<List<LocalDate>> { adj(done + it) }.thenComparator { a, b -> compareLex(a, b) })
        if (best == null) size--
    }
    val chosen = best ?: emptyList()
    val todayGym = today in chosen
    val canSkip = !todayGym || combos(free - today, chosen.size).any { valid(done + it) }
    return WeekPlan(done.size, (done + chosen).sorted(), todayGym, canSkip, done.size + chosen.size)
}

/** Нет ли в будних днях серии длиннее [maxRun] подряд. */
fun noLongRun(days: Set<LocalDate>, monday: LocalDate, maxRun: Int = 2): Boolean {
    var run = 0
    for (i in 0L..4L) { run = if (monday.plusDays(i) in days) run + 1 else 0; if (run > maxRun) return false }
    return true
}

/**
 * План недели, заданный вручную: [chosen] — выбранные будущие дни.
 * Сделанные дни всегда в плане; прошедшие несделанные — игнорируются.
 */
fun manualWeek(trained: Set<LocalDate>, chosen: Set<LocalDate>, today: LocalDate, target: Int = 3): WeekPlan {
    val monday = today.with(DayOfWeek.MONDAY)
    val week = (0L..6L).map { monday.plusDays(it) }.toSet()
    val done = trained.filter { it in week }.toSet()
    val future = chosen.filter { it in week && it >= today && it !in done }.toSet()
    val todayGym = today in future
    // перенести сегодня можно, если без него цель всё ещё достижима
    val canSkip = !todayGym || planWeek(done, today.plusDays(1), target).achievable >= target
    return WeekPlan(done.size, (done + future).sorted(), todayGym, canSkip, done.size + future.size)
}

private fun compareLex(a: List<LocalDate>, b: List<LocalDate>): Int {
    for (i in a.indices) { val c = a[i].compareTo(b[i]); if (c != 0) return c }
    return 0
}

private fun <T> combos(items: List<T>, k: Int): List<List<T>> {
    if (k == 0) return listOf(emptyList())
    if (items.size < k) return emptyList()
    val head = items.first(); val tail = items.drop(1)
    return combos(tail, k - 1).map { listOf(head) + it } + combos(tail, k)
}
