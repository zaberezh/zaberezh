package by.zaberezh.forma.core

import by.zaberezh.forma.core.daily.Streaks
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreakTest {
    private val today = LocalDate.of(2026, 10, 10)
    private fun days(vararg d: Int) = d.map { LocalDate.of(2026, 10, it) }.toSet()

    @Test fun countsConsecutiveDaysTodayStillOpen() {
        val s = Streaks.of(days(7, 8, 9), today)                // сегодня ещё не отмечено — серия жива
        assertEquals(3, s.days); assertFalse(s.doneToday)
        assertEquals(4, Streaks.of(days(7, 8, 9, 10), today).days)
    }

    @Test fun anyGapBreaksTheStreak() {
        assertEquals(4, Streaks.of(days(1, 2, 4, 5, 7, 8, 9, 10), today).days)   // пропуск 6-го — серия с 7-го
        assertEquals(0, Streaks.of(days(1, 2, 3, 8), today).days)                // вчера и сегодня пусто — серии нет
    }

    @Test fun flameOnlyFromTwoDaysInARow() {
        assertFalse(Streaks.of(days(10), today).alive)        // один день — ещё не огонёк
        assertTrue(Streaks.of(days(9, 10), today).alive)
        assertFalse(Streaks.of(emptySet(), today).alive)
    }

    @Test fun maxFromBestSetWhenNoTest() {
        val s = by.zaberezh.forma.core.store.MemoryStore()
        val d1 = LocalDate.of(2026, 10, 1); val d2 = LocalDate.of(2026, 10, 2)
        by.zaberezh.forma.core.daily.COUNT.save(s, by.zaberezh.forma.core.daily.DayCount("pullups", d1.toString(), 27, listOf(8, 10, 9)))
        by.zaberezh.forma.core.daily.COUNT.save(s, by.zaberezh.forma.core.daily.DayCount("pullups", d2.toString(), 20, listOf(12, 8)))
        by.zaberezh.forma.core.daily.TestModule.record(s, "pullups_max", d2, 14.0)            // тест в этот день важнее подходов
        assertEquals(listOf(d1 to 10.0, d2 to 14.0), by.zaberezh.forma.core.daily.TestModule.results(s, "pullups_max"))
        assertEquals(listOf(d2 to 14.0), by.zaberezh.forma.core.daily.TestModule.recorded(s, "pullups_max"))
    }
}
