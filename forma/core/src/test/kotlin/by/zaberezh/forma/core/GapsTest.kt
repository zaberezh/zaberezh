package by.zaberezh.forma.core

import by.zaberezh.forma.core.study.Gaps
import by.zaberezh.forma.core.study.Lesson
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class GapsTest {
    private fun l(a: String, b: String) = Lesson("X", start = a, end = b)

    @Test fun gapsBetweenClasses() {
        // 8:30–9:55, перемена 10 мин, 10:05–11:30, окно до 13:25, 13:25–14:50
        val g = Gaps.gaps(listOf(l("13:25", "14:50"), l("08:30", "09:55"), l("10:05", "11:30")))
        assertEquals(1, g.size)
        assertEquals(LocalTime.of(11, 30) to LocalTime.of(13, 25), g[0].from to g[0].to)
        assertEquals(115, g[0].minutes)
        // параллельные пары (подгруппы) не дают ложного окна
        assertEquals(0, Gaps.gaps(listOf(l("08:30", "11:30"), l("10:05", "11:30"), l("11:40", "13:05"))).size)
    }

    @Test fun gapLabels() {
        fun g(a: String, b: String) = by.zaberezh.forma.core.study.Gap(LocalTime.parse(a), LocalTime.parse(b))
        assertEquals("окно 11:30–13:25 · 1 ч 55 мин", g("11:30", "13:25").label())
        assertEquals("окно 10:00–12:00 · 2 ч", g("10:00", "12:00").label())
        assertEquals("окно 13:00–13:40 · 40 мин", g("13:00", "13:40").label())
    }
}
