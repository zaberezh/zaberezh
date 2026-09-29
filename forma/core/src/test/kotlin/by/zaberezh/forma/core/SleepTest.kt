package by.zaberezh.forma.core

import by.zaberezh.forma.core.sleep.detectNight
import by.zaberezh.forma.core.sleep.hm
import by.zaberezh.forma.core.sleep.nightWindow
import by.zaberezh.forma.core.store.ZONE
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SleepTest {
    private val day = LocalDate.of(2026, 9, 30)
    private fun t(d: LocalDate, h: Int, m: Int) = d.atTime(h, m).atZone(ZONE).toInstant().toEpochMilli()
    private val prev = day.minusDays(1)

    @Test fun nightWithPhoneCheck() {
        val (from, to) = nightWindow(day)
        val ev = listOf(
            t(prev, 21, 0) to true, t(prev, 21, 30) to false,   // вечер
            t(prev, 22, 0) to true, t(prev, 23, 40) to false,   // последний раз выключил экран
            t(day, 3, 10) to true, t(day, 3, 13) to false,      // проверил время ночью
            t(day, 7, 15) to true, t(day, 7, 30) to false,      // утро
            t(day, 12, 0) to true, t(day, 12, 5) to false,
        )
        val n = assertNotNull(detectNight(ev, from, to))
        assertEquals("23:40", hm(n.start)); assertEquals("07:15", hm(n.end))
        assertEquals(1, n.wakeups); assertEquals(7 * 60 + 35 - 3, n.minutes)
    }

    @Test fun stillSleepingIsNotFinal() {
        val (from, to) = nightWindow(day)
        val ev = listOf(t(prev, 22, 0) to true, t(prev, 23, 30) to false)
        assertNull(detectNight(ev, from, to, now = t(day, 6, 0)))
        assertNotNull(detectNight(ev + (t(day, 8, 0) to true), from, to, now = t(day, 9, 0)))
    }

    @Test fun noLongGapNoNight() {
        val (from, to) = nightWindow(day)
        val ev = (0 until 44).flatMap { i -> val s = from + i * 30 * 60_000L; listOf(s to true, s + 10 * 60_000L to false) }
        assertNull(detectNight(ev, from, to))
    }
}
