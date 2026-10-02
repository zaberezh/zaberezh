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
}
