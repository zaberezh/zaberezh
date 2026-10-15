package by.zaberezh.forma.core

import by.zaberezh.forma.core.gym.ExerciseInput
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.PROGRAM
import by.zaberezh.forma.core.gym.PlanItem
import by.zaberezh.forma.core.gym.Planner
import by.zaberezh.forma.core.gym.Program
import by.zaberezh.forma.core.gym.SetLog
import by.zaberezh.forma.core.gym.WORKOUT
import by.zaberezh.forma.core.gym.Workout
import by.zaberezh.forma.core.gym.progress
import by.zaberezh.forma.core.gym.upsert
import by.zaberezh.forma.core.store.MemoryStore
import by.zaberezh.forma.core.store.startMs
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Жим гантелей после жима лёжа — вес ниже, и это не падение силы: сравниваем тренировки в той же очерёдности. */
class OrderContextTest {
    private val today = LocalDate.of(2026, 10, 14)
    private var p = Program("Моя", 2, emptyList(), emptyList())
        .upsert(null, ExerciseInput("Жим штанги лёжа", 60.0, "6-10", 3, null, false, null))
        .upsert(null, ExerciseInput("Жим гантелей лежа 45°", 16.0, "10-14", 3, null, false, null))
        .upsert(null, ExerciseInput("Махи рукой в сторону на блоке", 10.0, "8-15", 3, null, false, null))
    private val bench = p.exercises[0].id
    private val db = p.exercises[1].id
    private val raise = p.exercises[2].id

    private fun store() = MemoryStore().also { PROGRAM.set(it, p); SETTINGS.set(it, Settings(schema = 99)) }
    private fun log(s: MemoryStore, daysAgo: Long, vararg sets: SetLog) {
        val d = today.minusDays(daysAgo)
        WORKOUT.save(s, Workout(d.toString(), d.startMs() + 3600_000, sets = sets.toList()), ts = d.startMs() + 3600_000)
    }
    private fun three(ex: String, w: Double, r: Int) = Array(3) { SetLog(ex, w, r) }

    @Test fun dumbbellPressAfterBenchIsNotAFall() {
        val s = store()
        log(s, 6, *three(db, 16.0, 14))                                  // первым — свежим
        log(s, 3, *three(bench, 60.0, 8), *three(db, 14.0, 10))         // после жима лёжа
        log(s, 1, *three(bench, 60.0, 8), *three(db, 14.0, 10))
        assertEquals(listOf(false, true, true), GymModule.exSessions(s, db).map { it.after })
        val x = GymModule.progress(Ctx(s, today), null).items.first { it.ex.id == db }
        assertTrue(x.mixed)
        assertEquals(listOf(14.0, 14.0), x.line.map { it.best.w })        // сравниваются только тренировки «после жима лёжа»
        assertEquals(0.0, x.change!!, 1e-9)                               // а не «сила −20%»
        assertTrue(x.trend.status != "падает", x.trend.status)
        // следующая после жима лёжа — от прошлых таких же: 14 кг и +1 повтор
        val after = GymModule.target(s, p.ex(db)!!, today, after = true, afterName = "Жим штанги лёжа")
        assertEquals(14.0, after.weight); assertEquals(listOf(11, 11, 11), after.reps)
        // а если сегодня первым — от тренировки, где тоже был первым: 16 × 14 = верх диапазона → +шаг
        assertEquals(18.0, GymModule.target(s, p.ex(db)!!, today, after = false).weight)
    }

    @Test fun firstTimeAfterBenchStartsLighter() {
        val s = store()
        log(s, 3, *three(db, 16.0, 14))
        val plan = listOf(PlanItem(bench, 3), PlanItem(db, 3))
        val (after, name) = GymModule.context(p, plan, db)
        assertTrue(after); assertEquals("Жим штанги лёжа", name)
        val t = GymModule.target(s, p.ex(db)!!, today, after = after, afterName = name)
        assertEquals(14.0, t.weight)                                     // ~10% легче, кратно шагу гантелей
        assertTrue("после «Жим штанги лёжа»" in t.note, t.note)
        // а в плане дня это делается само
        val day = by.zaberezh.forma.core.gym.DayPlan(today.toString(), plan)
        assertEquals(14.0, GymModule.targets(s, day)[1].weight)
    }

    @Test fun threeWorkoutsInAWeekAreNotATrendYet() {
        val s = store()
        log(s, 8, *three(raise, 10.0, 8)); log(s, 4, *three(raise, 7.0, 11)); log(s, 1, *three(raise, 5.0, 13))
        val x = GymModule.progress(Ctx(s, today), null).items.single()
        assertEquals("мало данных", x.trend.status)                      // а не «падает, −40% в неделю»
    }

    @Test fun weeklyVolumeCountsOnlyWeeksSinceStart() {
        val s = store()
        for (d in listOf(9L, 5L, 1L)) log(s, d, *three(raise, 5.0, 12))
        val c = GymModule.checkup(Ctx(s, today), today.minusDays(13), today)
        // 9 подходов за 10 дней ≈ 6.3 в неделю, а не 9 / 2 недели периода
        assertTrue(c.lines.any { "средняя дельта 6.3" in it }, "${c.lines}")
    }

    @Test fun startCountingAgainAfterChangingMachine() {
        val s = store()
        log(s, 30, *three(raise, 10.0, 8)); log(s, 20, *three(raise, 10.0, 9))
        log(s, 10, *three(raise, 5.0, 13)); log(s, 3, *three(raise, 5.0, 14))
        GymModule.setProgressFrom(s, raise, today.minusDays(10))
        val x = GymModule.progress(Ctx(s, today), null).items.single()
        assertEquals(listOf(5.0, 5.0), x.points.map { it.best.w })
        assertEquals(today.minusDays(10), x.from)
        GymModule.setProgressFrom(s, raise, null)
        assertEquals(4, GymModule.progress(Ctx(s, today), null).items.single().points.size)
    }

    @Test fun barbellBeforeDumbbellsEveryTime() {
        val order = Planner.arrange(p, listOf(PlanItem(db, 3), PlanItem(bench, 3)), supersets = false).map { it.ex }
        assertEquals(listOf(bench, db), order)
    }
}
