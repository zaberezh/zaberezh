package by.zaberezh.forma.core.daily

import by.zaberezh.forma.core.body.WEIGHT
import by.zaberezh.forma.core.store.Store
import java.time.LocalDate
import java.time.YearMonth

/**
 * Огонёк: сколько дней подряд. Пропуск закрывается автосейвом — не больше [SAVES_PER_MONTH] за календарный месяц;
 * сейв держит серию, но день не добавляет. Сегодня ещё не отмечено — серия не сгорает (день не закончился).
 */
data class Streak(val days: Int, val savedDays: List<LocalDate>, val savesLeft: Int, val doneToday: Boolean) {
    val alive get() = days > 0
}

object Streaks {
    const val SAVES_PER_MONTH = 2

    fun of(done: Set<LocalDate>, today: LocalDate): Streak {
        val used = mutableMapOf<YearMonth, Int>()
        val saved = mutableListOf<LocalDate>()
        val first = done.minOrNull() ?: return Streak(0, emptyList(), SAVES_PER_MONTH, false)
        var d = if (today in done) today else today.minusDays(1)
        var n = 0
        var earliest: LocalDate? = null
        while (!d.isBefore(first)) {
            if (d in done) { n++; earliest = d }
            else {
                val m = YearMonth.from(d)
                if ((used[m] ?: 0) >= SAVES_PER_MONTH) break
                used[m] = (used[m] ?: 0) + 1; saved += d
            }
            d = d.minusDays(1)
        }
        // сейвы, потраченные на дни перед началом серии, не считаются (серия на них не опиралась)
        val real = earliest?.let { e -> saved.filter { it.isAfter(e) } }.orEmpty()
        val thisMonth = real.count { YearMonth.from(it) == YearMonth.from(today) }
        return Streak(n, real.sorted(), (SAVES_PER_MONTH - thisMonth).coerceAtLeast(0), today in done)
    }

    /** Подтягивания: день засчитан, если есть подходы за день или записан максимум. */
    fun pullups(s: Store, today: LocalDate): Streak {
        val days = COUNT.all(s).filter { it.second.counter == "pullups" && (it.second.n > 0 || it.second.sets.isNotEmpty()) }
            .map { LocalDate.parse(it.second.date) }.toSet() +
            TEST.all(s).filter { it.second.test == "pullups_max" }.map { LocalDate.parse(it.second.date) }
        return of(days, today)
    }

    /** Взвешивания: день засчитан, если записан вес. */
    fun weighIns(s: Store, today: LocalDate): Streak = of(WEIGHT.all(s).map { it.first.day }.toSet(), today)
}
