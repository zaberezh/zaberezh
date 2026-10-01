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

    @Test fun twoSavesPerMonthBridgeGaps() {
        val s = Streaks.of(days(1, 2, 4, 5, 7, 8, 9, 10), today)  // пропуски 3 и 6 — два сейва
        assertEquals(8, s.days); assertEquals(listOf(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 6)), s.savedDays)
        assertEquals(0, s.savesLeft)
        val broken = Streaks.of(days(1, 3, 5, 7, 8, 9, 10), today) // третий пропуск в месяце — серия обрывается
        assertEquals(6, broken.days)                              // 10…7, сейв 6, 5, сейв 4, 3 — на 2-м сейвов уже нет
    }

    @Test fun savesResetEachMonth() {
        val sep = (20..30).filter { it != 22 && it != 25 }.map { LocalDate.of(2026, 9, it) }
        val oct = listOf(1, 2, 4, 5, 6, 7, 8, 9, 10).map { LocalDate.of(2026, 10, it) }   // 3 октября — сейв октября
        val s = Streaks.of((sep + oct).toSet(), today)
        assertEquals(sep.size + oct.size, s.days)
        assertEquals(1, s.savesLeft)
        assertTrue(Streaks.of(emptySet(), today).days == 0)
    }

    @Test fun trailingSavesDontCount() {
        val s = Streaks.of(days(1, 8, 9, 10), today)             // 7 и 6 закрылись бы сейвами, но до 1-го всё равно разрыв
        assertEquals(3, s.days); assertEquals(2, s.savesLeft)
    }
}
