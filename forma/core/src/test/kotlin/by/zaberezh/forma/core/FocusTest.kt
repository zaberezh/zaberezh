package by.zaberezh.forma.core

import by.zaberezh.forma.core.store.MemoryStore
import by.zaberezh.forma.core.study.Focus
import by.zaberezh.forma.core.study.Lesson
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FocusTest {
    private fun l(a: String, b: String) = Lesson("X", start = a, end = b)

    @Test fun gapsBetweenClasses() {
        // 8:30–9:55, перемена 10 мин, 10:05–11:30, окно до 13:25, 13:25–14:50
        val g = Focus.gaps(listOf(l("13:25", "14:50"), l("08:30", "09:55"), l("10:05", "11:30")))
        assertEquals(1, g.size)
        assertEquals(LocalTime.of(11, 30) to LocalTime.of(13, 25), g[0].from to g[0].to)
        assertEquals(115, g[0].minutes)
        // параллельные пары (подгруппы) не дают ложного окна
        assertEquals(0, Focus.gaps(listOf(l("08:30", "11:30"), l("10:05", "11:30"), l("11:40", "13:05"))).size)
    }

    @Test fun focusSessionsCountRealMinutes() {
        val s = MemoryStore()
        val t0 = 1_790_000_000_000L
        Focus.start(s, "lab:1", t0)
        assertEquals(25, Focus.active(s)!!.left(t0))
        assertEquals(10, Focus.stop(s, t0 + 10 * 60_000)!!.minutes)        // остановил раньше — 10 минут
        assertNull(Focus.active(s))
        Focus.start(s, "lab:1", t0 + 3_600_000)
        assertEquals(25, Focus.stop(s, t0 + 3_600_000 + 40 * 60_000)!!.minutes)   // забыл выключить — не больше плана
        assertEquals(35, Focus.minutes(s, "lab:1"))
        Focus.start(s, "lab:2", t0); assertNull(Focus.stop(s, t0 + 20_000))       // меньше минуты — не считается
    }
}
