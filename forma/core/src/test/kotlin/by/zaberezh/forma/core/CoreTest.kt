package by.zaberezh.forma.core

import by.zaberezh.forma.core.body.WEIGHT
import by.zaberezh.forma.core.body.Weight
import by.zaberezh.forma.core.body.navyBodyFat
import by.zaberezh.forma.core.body.weightRate
import by.zaberezh.forma.core.daily.CounterModule
import by.zaberezh.forma.core.food.FoodItem
import by.zaberezh.forma.core.food.FoodModule
import by.zaberezh.forma.core.food.LibraryResolver
import by.zaberezh.forma.core.food.MEAL
import by.zaberezh.forma.core.food.Macro
import by.zaberezh.forma.core.food.Meal
import by.zaberezh.forma.core.food.adaptiveTdee
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.PROGRAM
import by.zaberezh.forma.core.gym.SetLog
import by.zaberezh.forma.core.gym.WORKOUT
import by.zaberezh.forma.core.gym.Workout
import by.zaberezh.forma.core.gym.Program
import by.zaberezh.forma.core.gym.defaultProgram
import by.zaberezh.forma.core.gym.parseProgram
import by.zaberezh.forma.core.store.JSON
import by.zaberezh.forma.core.gym.nextTarget
import by.zaberezh.forma.core.gym.planWeek
import by.zaberezh.forma.core.report.Checkup
import by.zaberezh.forma.core.store.MemoryStore
import by.zaberezh.forma.core.store.startMs
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val mon = LocalDate.of(2026, 9, 28) // понедельник
    private val prog = parseProgram(javaClass.getResource("/test_program.json")!!.readText())
    private fun store() = MemoryStore().also { PROGRAM.set(it, prog) }

    @Test fun weekPlanPrefersMonWedFri() {
        val p = planWeek(emptySet(), mon)
        assertEquals(listOf(mon, mon.plusDays(2), mon.plusDays(4)), p.plan)
        assertTrue(p.todayGym); assertTrue(p.canSkipToday)
        assertFalse(planWeek(setOf(mon), mon.plusDays(1)).todayGym) // вторник после понедельника — отдых
    }

    @Test fun weekPlanNeverThreeInARow() {
        val tue = mon.plusDays(1)
        val p = planWeek(emptySet(), tue) // понедельник пропущен
        assertTrue(p.todayGym); assertFalse(p.canSkipToday)
        assertEquals(3, p.achievable)
        val wed = mon.plusDays(2)
        val q = planWeek(setOf(mon, tue), wed) // Пн+Вт сделаны: среда запрещена
        assertFalse(q.todayGym)
        assertEquals(listOf(mon, tue, mon.plusDays(3)), q.plan)
        val r = planWeek(emptySet(), mon.plusDays(3)) // четверг, ничего нет — максимум 2
        assertEquals(2, r.achievable)
        assertFalse(planWeek(emptySet(), mon.plusDays(5)).todayGym) // суббота
    }

    @Test fun manualWeekToggle() {
        val s = MemoryStore()
        val ctx = Ctx(s, mon)
        assertEquals(listOf(mon, mon.plusDays(2), mon.plusDays(4)), GymModule.week(ctx).plan) // авто Пн/Ср/Пт
        assertNull(GymModule.toggleDay(ctx, mon))                // снять понедельник
        assertNull(GymModule.toggleDay(ctx, mon.plusDays(1)))    // поставить вторник
        val wk = GymModule.week(Ctx(s, mon))
        assertEquals(listOf(mon.plusDays(1), mon.plusDays(2), mon.plusDays(4)), wk.plan)
        assertFalse(wk.todayGym)
        assertFalse(GymModule.threeInRow(ctx))
        assertNull(GymModule.toggleDay(ctx, mon.plusDays(3)))     // Вт+Ср+Чт — можно, но с предупреждением
        assertTrue(GymModule.threeInRow(ctx))
        assertNull(GymModule.toggleDay(ctx, mon.plusDays(3)))
        assertNotNull(GymModule.toggleDay(ctx, mon.plusDays(5)))  // суббота
        GymModule.resetWeek(ctx)
        assertEquals(listOf(mon, mon.plusDays(2), mon.plusDays(4)), GymModule.week(ctx).plan)
    }

    @Test fun dailyCounter() {
        val s = MemoryStore()
        val ctx = Ctx(s, mon)
        assertEquals(0, CounterModule.get(s, "pullups", mon))
        CounterModule.set(s, "pullups", mon.minusDays(1), 12)
        CounterModule.set(s, "pullups", mon.minusDays(1), 15) // редактирование
        assertEquals(15, CounterModule.get(s, "pullups", mon.minusDays(1)))
        assertEquals(listOf(0, 15, 0), CounterModule.series(s, "pullups", mon.minusDays(2), mon).map { it.second })
        assertTrue(CounterModule.morning(ctx).first().contains("вчера: 15"))
        assertNotNull(CounterModule.checkup(ctx, mon.minusDays(13), mon))
    }

    @Test fun doubleProgression() {
        val ex = prog.ex("bench")!! // 3x6-10, шаг 2.5
        assertNull(nextTarget(ex, emptyList()).weight)
        val up = nextTarget(ex, listOf(List(3) { SetLog("bench", 60.0, 10) }))
        assertEquals(62.5, up.weight); assertEquals(listOf(6, 6, 6), up.reps)
        val hold = nextTarget(ex, listOf(listOf(SetLog("bench", 60.0, 8), SetLog("bench", 60.0, 7), SetLog("bench", 60.0, 6))))
        assertEquals(60.0, hold.weight); assertEquals(listOf(9, 8, 7), hold.reps)
        val stuck = List(3) { listOf(SetLog("bench", 60.0, 7), SetLog("bench", 60.0, 6), SetLog("bench", 60.0, 6)) }
        assertEquals(55.0, nextTarget(ex, stuck).weight)
    }

    @Test fun programRotationAndTargets() {
        val s = store()
        assertEquals("A", GymModule.nextDay(s).id)
        WORKOUT.save(s, Workout("A", mon.startMs(), sets = listOf(SetLog("bench", 50.0, 10))), ts = mon.startMs() + 3600_000)
        assertEquals("B", GymModule.nextDay(s).id)
        val t = GymModule.targets(s, PROGRAM.get(s).day("A")!!)
        assertEquals(50.0, t.first().weight)
    }

    @Test fun nutritionTargetsAndAdaptiveTdee() {
        val s = MemoryStore()
        val ctx = Ctx(s, mon)
        val t = FoodModule.targets(ctx)
        assertTrue(t.kcal in 2450..2600, "kcal=${t.kcal}")
        assertEquals(141, t.p)
        // 28 дней по 2800 ккал, вес растёт 0.1 кг/нед -> TDEE ≈ 2800 − 110
        val w = (0..27).map { mon.minusDays(27L - it) to 70.0 + it * 0.1 / 7 }
        val intake = w.associate { it.first to 2800.0 }
        val (tdee, conf) = adaptiveTdee(w, intake, mon)!!
        assertTrue(abs(tdee - 2690) < 5, "tdee=$tdee"); assertEquals(1.0, conf)
    }

    @Test fun defaultProgramIsEmptyAndSafe() {
        val s = MemoryStore()
        assertTrue(defaultProgram().exercises.isEmpty())
        assertEquals("A", GymModule.nextDay(s).id)
        assertTrue(GymModule.targets(s, GymModule.nextDay(s)).isEmpty())
        s.kvPut(PROGRAM.key, JSON.encodeToString(Program.serializer(), prog)) // старый черновик
        migrateSettings(s)
        assertTrue(PROGRAM.get(s).exercises.isEmpty())
    }

    @Test fun settingsMigrationDropsWalking() {
        val s = MemoryStore()
        SETTINGS.set(s, Settings(profile = Profile(activity = 1.55), apiKey = "k"))
        migrateSettings(s)
        assertEquals(1.375, SETTINGS.get(s).profile.activity)
        assertEquals("k", SETTINGS.get(s).apiKey)
        SETTINGS.set(s, SETTINGS.get(s).copy(profile = Profile(activity = 1.6)))
        migrateSettings(s) // уже v2 — не трогаем
        assertEquals(1.6, SETTINGS.get(s).profile.activity)
    }

    @Test fun libraryResolver() {
        val s = MemoryStore()
        FoodModule.remember(s, listOf(FoodItem("Гречка", 200.0, Macro(110.0, 4.0, 1.0, 21.0)), FoodItem("Яйцо", 55.0, Macro(157.0, 12.7, 11.5, 0.7))))
        val r = LibraryResolver(s).resolve("гречка 250г, 2 яйцо")!!
        assertEquals(250.0, r[0].grams); assertEquals(110.0, r[1].grams)
        assertEquals(275.0, r[0].total.kcal)
        assertNull(LibraryResolver(s).resolve("шаурма"))
    }

    @Test fun bodyFatAndRate() {
        val bf = navyBodyFat(80.0, 37.0, 180.0)!!
        assertTrue(bf in 12.0..17.0, "bf=$bf")
        val s = MemoryStore()
        (0..20).forEach { WEIGHT.save(s, Weight(70.0 + it * 0.02), ts = mon.minusDays(20L - it).startMs() + 7 * 3600_000) }
        assertEquals(0.14, weightRate(s, mon)!!, 0.01)
    }

    @Test fun checkupRendersAllModules() {
        val s = store()
        (0..13).forEach { d ->
            val day = mon.minusDays(13L - d)
            WEIGHT.save(s, Weight(70.5 + d * 0.01), ts = day.startMs() + 7 * 3600_000)
            MEAL.save(s, Meal("x", listOf(FoodItem("еда", 1000.0, Macro(270.0, 14.0, 7.0, 38.0)))), ts = day.startMs() + 12 * 3600_000)
            if (d % 2 == 0 && day.dayOfWeek.value <= 5)
                WORKOUT.save(s, Workout("A", day.startMs(), sets = listOf(SetLog("bench", 50.0 + d, 8), SetLog("curl", 30.0, 10))), ts = day.startMs() + 18 * 3600_000)
        }
        val ctx = Ctx(s, mon)
        val secs = Checkup.sections(ctx, mon.minusDays(13), mon)
        val text = Checkup.render(mon.minusDays(13), mon, secs)
        assertTrue("## Зал" in text && "## Тело" in text && "## Питание" in text, text)
        assertTrue("Жим штанги лёжа" in text, text)
        assertNotNull(Checkup.facts(ctx, mon.minusDays(13), mon, secs))
        assertFalse(Checkup.due(ctx)); assertTrue(Checkup.due(Ctx(s, mon.plusDays(1))))
        val mo = Checkup.morning(Ctx(s, mon.plusDays(2)))
        assertTrue(mo.any { it.startsWith("Зал сегодня") }, mo.toString())
    }
}
