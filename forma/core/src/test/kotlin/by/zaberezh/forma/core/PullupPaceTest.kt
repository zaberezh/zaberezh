package by.zaberezh.forma.core

import by.zaberezh.forma.core.daily.CounterModule
import by.zaberezh.forma.core.daily.TestModule
import by.zaberezh.forma.core.store.MemoryStore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Темп роста подтягиваний: максимум за подход против плана и недельный объём. */
class PullupPaceTest {
    private val today = LocalDate.of(2026, 10, 14)
    private fun store(level: Int = 0) = MemoryStore().also { SETTINGS.set(it, Settings(gymLevel = level, schema = 99)) }

    @Test fun weeklyBestAgainstPlan() {
        val s = store()
        // каждую неделю один тест (+1 раз в неделю) и подходы не до отказа в другие дни — они максимум не занижают
        for (w in 0..5) {
            val d = today.minusDays(7L * (5 - w))
            TestModule.record(s, "pullups_max", d, 8.0 + w)
            CounterModule.addSet(s, "pullups", d.minusDays(2), 5)
        }
        val p = TestModule.pace(Ctx(s, today), "pullups_max")
        assertEquals(listOf(8.0, 9.0, 10.0, 11.0, 12.0, 13.0), p.weeks.map { it.second })
        assertEquals(1.0, p.perWeek!!, 1e-9)
        assertEquals((30 + 13) / 100.0, p.plan, 1e-9)        // +1% силы при 13 разах ≈ +0,43 раза в неделю
        assertEquals("растёт", p.status)
        assertEquals(13 + 4 * 0.43, p.goal(4)!!, 1e-9)
    }

    @Test fun stuckMaxAndFewWeeks() {
        val s = store(level = 1)
        for (w in 0..3) CounterModule.addSet(s, "pullups", today.minusDays(7L * w), 10)
        assertEquals("стоит", TestModule.pace(Ctx(s, today), "pullups_max").status)
        val one = store(); CounterModule.addSet(one, "pullups", today, 10)
        assertEquals("мало данных", TestModule.pace(Ctx(one, today), "pullups_max").status)
    }

    @Test fun weeklyVolumePace() {
        val s = store()
        // 4 недели: по 20, 30, 40, 50 за неделю
        for (w in 0..3) CounterModule.addSet(s, "pullups", today.minusDays(7L * (3 - w)), 20 + 10 * w)
        val weekly = CounterModule.weekly(s, "pullups", today)
        assertEquals(listOf(0, 0, 20, 30, 40, 50), weekly.map { it.second })
        assertEquals(10.0, CounterModule.weeklyPace(weekly)!!, 1e-9)
        assertNull(CounterModule.weeklyPace(CounterModule.weekly(store(), "pullups", today)))
        assertTrue(weekly.last().first == today)
    }
}
