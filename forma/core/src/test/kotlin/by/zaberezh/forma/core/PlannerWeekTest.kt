package by.zaberezh.forma.core

import by.zaberezh.forma.core.gym.DayPlan
import by.zaberezh.forma.core.gym.FOREARM_SEED
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.PROGRAM
import by.zaberezh.forma.core.gym.SetLog
import by.zaberezh.forma.core.gym.WORKOUT
import by.zaberezh.forma.core.gym.Workout
import by.zaberezh.forma.core.gym.parseProgram
import by.zaberezh.forma.core.store.MemoryStore
import by.zaberezh.forma.core.store.startMs
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Две недели по плану приложения: как распределяются упражнения по мышцам. */
class PlannerWeekTest {
    private val prog = parseProgram(javaClass.getResource("/test_program.json")!!.readText()).let { it.copy(exercises = it.exercises + FOREARM_SEED) }
    private val mon = LocalDate.of(2026, 10, 5)

    /** Прожить две недели: каждую тренировку сделать ровно по плану. */
    private fun twoWeeks(split: String): List<DayPlan> {
        val s = MemoryStore(); PROGRAM.set(s, prog); SETTINGS.set(s, Settings(split = split, schema = 99))
        val out = mutableListOf<DayPlan>()
        for (w in 0..1) for (d in GymModule.week(Ctx(s, mon.plusWeeks(w.toLong()))).plan) {
            val plan = GymModule.planFor(Ctx(s, d), d)
            out += plan
            GymModule.savePlan(s, plan.copy(source = "edited"))
            WORKOUT.save(s, Workout(d.toString(), d.startMs() + 3600_000, sets = plan.items.flatMap { i -> List(i.sets) { SetLog(i.ex, 20.0, 10) } }),
                ts = d.startMs() + 3600_000)
        }
        return out
    }

    private fun mains(pl: DayPlan) = pl.items.map { prog.ex(it.ex)!!.muscles.maxBy { m -> m.value }.key }
    private fun count(pl: DayPlan, m: String) = mains(pl).count { it == m }

    @Test fun upperLowerArmsEverySessionAndNotTheSameDayTwice() {
        val plans = twoWeeks("ul")
        assertEquals(6, plans.size)
        plans.forEach { pl ->
            assertTrue(count(pl, "triceps") >= 1 && count(pl, "biceps") >= 1, "руки в каждой тренировке: ${mains(pl)}")
            assertTrue(pl.items.sumOf { it.sets } in 20..26, "подходов: ${pl.items}")
        }
        val upper = plans.filter { it.day == "upper" }
        // не «2 на грудь и 2 на спину» в каждый «Верх»: в неделю с двумя «Верхами» объём делится между ними
        val week1 = upper.filter { LocalDate.parse(it.date) < mon.plusWeeks(1) }
        assertEquals(2, week1.size)
        assertTrue(week1.any { count(it, "chest") == 1 }, "грудь: ${week1.map { count(it, "chest") }}")
        assertTrue(week1.map { mains(it).filter { m -> m == "chest" || m == "back" }.size }.sum() <= 7, "${week1.map(::mains)}")
        // разные варианты в разные дни: два «Верха» недели не повторяют упражнения на грудь
        val chest = week1.map { pl -> pl.items.map { it.ex }.filter { prog.ex(it)!!.muscles.maxBy { m -> m.value }.key == "chest" }.toSet() }
        assertTrue((chest[0] intersect chest[1]).isEmpty(), "грудь по дням: $chest")
    }

    @Test fun fullBodyOneChestOneTricepsEachDay() {
        twoWeeks("full").forEach { pl ->
            assertEquals(1, count(pl, "chest"), "${mains(pl)}")
            assertTrue(count(pl, "triceps") == 1 && count(pl, "biceps") == 1, "${mains(pl)}")
            assertTrue(count(pl, "quads") >= 1 && count(pl, "hams") >= 1, "${mains(pl)}")
        }
    }

    @Test fun weeklyArmVolumeIsReal() {
        // прямые подходы на трицепс за неделю «Верх/Низ» — в рабочем диапазоне 6–12+, а не 2
        val plans = twoWeeks("ul")
        for (w in 0..1) {
            val week = plans.filter { LocalDate.parse(it.date).let { d -> d >= mon.plusWeeks(w.toLong()) && d < mon.plusWeeks(w + 1L) } }
            val tri = week.sumOf { pl -> pl.items.filter { prog.ex(it.ex)!!.muscles["triceps"] == 1.0 }.sumOf { it.sets } }
            assertTrue(tri >= 6, "трицепс за неделю $w: $tri")
        }
    }
}
