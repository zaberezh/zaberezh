package by.zaberezh.forma.core.daily

import by.zaberezh.forma.core.body.WEIGHT
import by.zaberezh.forma.core.store.Store
import java.time.LocalDate

/**
 * Огонёк: сколько дней подряд, без пропусков. Загорается со второго дня подряд.
 * Сегодня ещё не отмечено — серия не сгорает (день не закончился).
 */
data class Streak(val days: Int, val doneToday: Boolean) {
    val alive get() = days >= 2
}

object Streaks {
    fun of(done: Set<LocalDate>, today: LocalDate): Streak {
        var d = if (today in done) today else today.minusDays(1)
        var n = 0
        while (d in done) { n++; d = d.minusDays(1) }
        return Streak(n, today in done)
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
