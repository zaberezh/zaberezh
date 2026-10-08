package by.zaberezh.forma.core

import by.zaberezh.forma.core.body.WEIGHT
import by.zaberezh.forma.core.body.Weight
import by.zaberezh.forma.core.body.navyBodyFat
import by.zaberezh.forma.core.body.weightRate
import by.zaberezh.forma.core.daily.CounterModule
import by.zaberezh.forma.core.daily.TestModule
import by.zaberezh.forma.core.food.FoodItem
import by.zaberezh.forma.core.food.FoodModule
import by.zaberezh.forma.core.food.LibraryResolver
import by.zaberezh.forma.core.food.MEAL
import by.zaberezh.forma.core.food.Macro
import by.zaberezh.forma.core.food.Meal
import by.zaberezh.forma.core.food.adaptiveTdee
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.PROGRAM
import by.zaberezh.forma.core.gym.Planner
import by.zaberezh.forma.core.gym.FOREARM_SEED
import by.zaberezh.forma.core.gym.PlanItem
import by.zaberezh.forma.core.gym.SetLog
import by.zaberezh.forma.core.gym.WORKOUT
import by.zaberezh.forma.core.gym.Workout
import by.zaberezh.forma.core.gym.Program
import by.zaberezh.forma.core.gym.defaultProgram
import by.zaberezh.forma.core.gym.parseProgram
import by.zaberezh.forma.core.store.JSON
import by.zaberezh.forma.core.gym.nextTarget
import by.zaberezh.forma.core.gym.noLongRun
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
        assertNull(GymModule.toggleDay(ctx, mon.plusDays(5)))     // суббота — можно
        assertTrue(mon.plusDays(5) in GymModule.week(ctx).plan)
        SETTINGS.set(s, Settings(gymWeekends = false))
        assertNotNull(GymModule.toggleDay(Ctx(s, mon), mon.plusDays(6)))  // воскресенье при выключенных выходных
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
        CounterModule.addSet(s, "pullups", mon, 8); CounterModule.addSet(s, "pullups", mon, 7); CounterModule.addSet(s, "pullups", mon, 6)
        assertEquals(21, CounterModule.get(s, "pullups", mon))
        CounterModule.removeSet(s, "pullups", mon, 1)
        assertEquals(listOf(8, 6), CounterModule.day(s, "pullups", mon).sets)
        CounterModule.addSet(s, "pullups", mon.minusDays(1), 5) // был итог 15 числом → 15 + 5
        assertEquals(listOf(15, 5), CounterModule.day(s, "pullups", mon.minusDays(1)).sets)
        assertEquals(mon.minusDays(1) to 15, CounterModule.bestSet(s, "pullups"))
    }

    @Test fun dailyMaxTest() {
        val s = MemoryStore()
        val daily = Settings().tests.first()
        assertEquals(1, daily.everyDays)                                     // максимум — каждый день
        TestModule.record(s, daily.id, mon, 10.0)
        assertFalse(TestModule.due(Ctx(s, mon), daily))
        assertTrue(TestModule.due(Ctx(s, mon.plusDays(1)), daily))
        // старые настройки (раз в неделю) переводятся на каждый день
        val old = MemoryStore()
        SETTINGS.set(old, Settings(schema = 5, tests = listOf(daily.copy(everyDays = 7))))
        migrateSettings(old)
        assertEquals(1, SETTINGS.get(old).tests.single().everyDays)
    }

    @Test fun weeklyTest() {
        val s = MemoryStore()
        val def = Settings().tests.first().copy(everyDays = 7)
        assertTrue(TestModule.due(Ctx(s, mon), def))
        TestModule.record(s, def.id, mon, 8.0)
        TestModule.record(s, def.id, mon, 9.0) // исправление в тот же день
        assertEquals(listOf(mon to 9.0), TestModule.results(s, def.id))
        assertFalse(TestModule.due(Ctx(s, mon.plusDays(6)), def))
        assertTrue(TestModule.due(Ctx(s, mon.plusDays(7)), def))
        TestModule.record(s, def.id, mon.plusDays(7), 11.0)
        assertTrue(TestModule.checkup(Ctx(s, mon.plusDays(7)), mon.plusDays(1), mon.plusDays(7))!!.lines.first().contains("9 → 11"))
    }

    @Test fun weekendsInAutoPlan() {
        val thu = mon.plusDays(3)
        assertEquals(2, planWeek(emptySet(), thu).achievable)                  // только будни
        val p = planWeek(emptySet(), thu, weekends = true)
        assertEquals(3, p.achievable)
        assertEquals(listOf(thu, mon.plusDays(5), mon.plusDays(6)).size, p.plan.size)
        assertTrue(noLongRun(p.plan.toSet(), mon))
    }

    @Test fun weighReminderTimes() {
        val st = Settings()
        val fri = mon.plusDays(4)
        assertEquals(fri.atTime(7, 20), by.zaberezh.forma.core.body.nextWeighTime(st, fri.atTime(6, 0)))
        assertEquals(mon.plusDays(5).atTime(11, 0), by.zaberezh.forma.core.body.nextWeighTime(st, fri.atTime(8, 0)))   // пт после 7:20 → сб 11:00
        assertEquals(mon.plusDays(7).atTime(7, 20), by.zaberezh.forma.core.body.nextWeighTime(st, mon.plusDays(6).atTime(12, 0))) // вс днём → пн 7:20
    }

    @Test fun orderByBlocks() {
        val s = store()
        val p = PROGRAM.get(s)
        val items = listOf("curl", "lateral_raise", "squat", "bench", "pushdown", "lat_pulldown", "hanging_raise", "ohp", "face_pull")
            .filter { p.ex(it) != null }.map { PlanItem(it, 3) }
        // блоками: ноги → жимы (грудь → дельты → трицепс) → тяги (спина → бицепс) → пресс
        val a = Planner.arrange(p, items, supersets = false).map { it.ex }
        assertEquals(listOf("squat", "bench", "ohp", "lateral_raise", "pushdown", "lat_pulldown", "face_pull", "curl", "hanging_raise")
            .filter { p.ex(it) != null }, a)
        // акцент «Спина» — тяги первыми, пресс всё равно последний
        val b = Planner.arrange(p, items, supersets = false, focus = "back").map { it.ex }
        assertEquals("lat_pulldown", b.first()); assertEquals("hanging_raise", b.last())
        // суперсеты (выключены по умолчанию) по-прежнему собирают пары жим ↔ тяга, бицепс ↔ трицепс
        val c = Planner.arrange(p, items, supersets = true)
        assertEquals(c.first { it.ex == "curl" }.pair, c.first { it.ex == "pushdown" }.pair)
    }

    @Test fun addedExerciseGoesToItsMuscleGroup() {
        val p = PROGRAM.get(store())
        val day = listOf("bench", "lat_pulldown", "hanging_raise").filter { p.ex(it) != null }.map { PlanItem(it, 3) }
        assertEquals(3, day.size)
        // разгибания на трицепс — после жима, перед тягами, а не после пресса
        assertEquals(listOf("bench", "pushdown", "lat_pulldown", "hanging_raise"), Planner.insert(p, day, PlanItem("pushdown", 3), false).map { it.ex })
        // присед — в самое начало
        assertEquals("squat", Planner.insert(p, day, PlanItem("squat", 3), false).first().ex)
    }

    @Test fun splitDaysRotateAndStayInTheirZone() {
        val s = store()
        val p = PROGRAM.get(s)
        val ctx = Ctx(s, mon)
        val days = GymModule.week(ctx).plan
        val plans = days.map { GymModule.planFor(ctx, it) }
        assertEquals(listOf("upper", "lower", "upper"), plans.map { it.day })            // по умолчанию Верх / Низ по очереди
        val legs = setOf("quads", "hams", "glutes", "calves")
        fun mains(pl: by.zaberezh.forma.core.gym.DayPlan) = pl.items.map { p.ex(it.ex)!!.muscles.maxBy { m -> m.value }.key }.toSet()
        assertTrue(mains(plans[0]).none { it in legs }, "верх без ног: ${plans[0]}")
        assertTrue(mains(plans[1]).any { it in legs } && mains(plans[1]).none { it in setOf("chest", "back", "side_delts") }, "низ: ${plans[1]}")
        // сделанная тренировка «Верх» → следующая по очереди «Низ», даже через пропуск
        GymModule.savePlan(s, plans[0])
        WORKOUT.save(s, Workout(mon.toString(), mon.startMs(), sets = listOf(SetLog("bench", 60.0, 8))), ts = mon.startMs() + 3600_000)
        val thu = Ctx(s, mon.plusDays(3))
        assertEquals("lower", GymModule.dayType(thu, GymModule.week(thu).plan.first { it >= mon.plusDays(3) }).id)
        // ручной выбор дня и PPL
        assertEquals("lower", GymModule.planFor(ctx, mon.plusDays(2), day = "lower").day)
        SETTINGS.set(s, SETTINGS.get(s).copy(split = "ppl"))
        val ppl = Ctx(MemoryStore().also { PROGRAM.set(it, prog); SETTINGS.set(it, Settings(split = "ppl")) }, mon)
        val pp = GymModule.week(ppl).plan.map { GymModule.planFor(ppl, it) }
        assertEquals(listOf("push", "pull", "legs"), pp.map { it.day })
        assertTrue(mains(pp[0]).all { it in setOf("chest", "front_delts", "side_delts", "triceps") }, "жим: ${pp[0]}")
        // акцент «Ноги» в день «Верх» переводит день в «Низ»
        assertEquals("lower", GymModule.planFor(ctx, mon, "legs").day)
    }

    @Test fun gripWorkLastEvenWithBackFocus() {
        val p = prog.copy(exercises = prog.exercises + FOREARM_SEED)
        val items = listOf("fa_reverse_curl", "bench", "lat_pulldown", "squat", "hanging_raise").map { PlanItem(it, 3) }
        assertEquals(listOf("squat", "bench", "lat_pulldown", "fa_reverse_curl", "hanging_raise"), Planner.arrange(p, items, false).map { it.ex })
        assertEquals(listOf("lat_pulldown", "squat", "bench", "fa_reverse_curl", "hanging_raise"), Planner.arrange(p, items, false, "back").map { it.ex })
    }

    @Test fun forearmEmphasis() {
        val s = MemoryStore()
        PROGRAM.set(s, prog)
        SETTINGS.set(s, Settings(schema = 4))
        migrateSettings(s) // v5: добавляет упражнения на предплечья
        val p = PROGRAM.get(s)
        assertEquals(3, p.exercises.count { (it.muscles["forearms"] ?: 0.0) >= 1.0 })
        migrateSettings(s) // повторно не дублирует
        assertEquals(p.exercises.size, PROGRAM.get(s).exercises.size)
        // за неделю предплечья попадают в план, всегда в конце (после тяг)
        val ctx = Ctx(s, mon)
        val week = GymModule.week(ctx).plan.map { GymModule.planFor(ctx, it) }
        val fa = week.sumOf { pl -> pl.items.filter { (p.ex(it.ex)!!.muscles["forearms"] ?: 0.0) >= 1.0 }.sumOf { it.sets } }
        assertTrue(fa >= 5, "подходов на предплечья за неделю: $fa")
        week.forEach { pl ->
            val i = pl.items.indexOfFirst { (p.ex(it.ex)!!.muscles["forearms"] ?: 0.0) >= 1.0 }
            val lastPull = pl.items.indexOfLast { by.zaberezh.forma.core.gym.isCompound(p.ex(it.ex)!!) }
            if (i >= 0) assertTrue(i > lastPull, "предплечья после базовых: ${pl.items}")
        }
        // акцент дня «Предплечья»
        val f = GymModule.planFor(ctx, mon, "forearms")
        assertTrue(f.items.count { (p.ex(it.ex)!!.muscles["forearms"] ?: 0.0) >= 1.0 } >= 2)
        assertEquals(mapOf("forearms" to 1.0, "biceps" to 0.5), by.zaberezh.forma.core.gym.guessMuscles("Подъём штанги обратным хватом"))
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

    @Test fun dailyPlanFromExerciseBase() {
        val s = store()
        val ctx = Ctx(s, mon)
        val plan = GymModule.planFor(ctx, mon)
        val p = PROGRAM.get(s)
        val sets = plan.items.sumOf { it.sets }
        assertTrue(sets in 18..27, "sets=$sets plan=$plan")                      // 90 мин ≈ 25 подходов
        assertTrue(plan.items.size in 5..9, "$plan")
        // на крупные мышцы верха — по 2 упражнения (если в базе есть)
        fun count(m: String) = plan.items.count { p.ex(it.ex)!!.muscles.maxBy { x -> x.value }.key == m }
        assertTrue(count("chest") >= 2 || count("back") >= 2, "2 упражнения на крупную мышцу: $plan")
        assertTrue(p.ex(plan.items.first().ex)!!.muscles.size > 1, "сначала базовое")
        // все крупные мышцы верха покрыты в фулбоди-дне
        val m = Planner.planMuscles(p, plan)
        listOf("chest", "back", "side_delts").forEach { assertTrue((m[it] ?: 0.0) >= 2, "$it: $m") }

        // вчера грудь убита -> сегодня грудь почти не берётся
        WORKOUT.save(s, Workout(mon.minusDays(1).toString(), mon.minusDays(1).startMs(),
            sets = List(4) { SetLog("bench", 60.0, 8) } + List(4) { SetLog("incline_db", 24.0, 10) }), ts = mon.minusDays(1).startMs() + 3600_000)
        val after = Planner.planMuscles(p, GymModule.planFor(Ctx(s, mon), mon))
        assertTrue((after["chest"] ?: 0.0) < (m["chest"] ?: 0.0), "chest $after vs $m")

        // фокус «руки» — больше бицепса/трицепса
        val arms = Planner.planMuscles(p, GymModule.planFor(ctx, mon, focus = "arms"))
        assertTrue((arms["biceps"] ?: 0.0) + (arms["triceps"] ?: 0.0) > (m["biceps"] ?: 0.0) + (m["triceps"] ?: 0.0), "$arms vs $m")

        // будущие дни недели не копируют сегодняшний: симуляция учитывает запланированное
        val wed = GymModule.planFor(Ctx(s, mon), mon.plusDays(2))
        assertTrue(wed.items.isNotEmpty(), "$wed")

        // правка сохраняется и возвращается как есть
        GymModule.savePlan(s, plan.copy(items = plan.items.drop(1), source = "edited"))
        assertEquals(plan.items.size - 1, GymModule.planFor(Ctx(s, mon), mon).items.size)
        val t = GymModule.targets(s, plan)
        assertEquals(plan.items.size, t.size)
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
        assertTrue(GymModule.planFor(Ctx(s, mon), mon).items.isEmpty())
        s.kvPut(PROGRAM.key, JSON.encodeToString(Program.serializer(), prog)) // старый черновик
        migrateSettings(s)
        assertTrue(PROGRAM.get(s).exercises.all { it.id.startsWith("fa_") }) // черновик удалён, остались только упражнения на предплечья
    }

    @Test fun freshInstallHasNoExercisesOrFood() {
        val s = MemoryStore()
        migrateSettings(s)                                                    // первый запуск на новом устройстве
        assertTrue(PROGRAM.get(s).exercises.isEmpty())
        assertTrue(s.kvGet(PROGRAM.key) == null)                              // программа даже не сохранялась
        assertEquals(SETTINGS_SCHEMA, SETTINGS.get(s).schema)
        migrateSettings(s)                                                    // и при следующих запусках тоже
        assertTrue(PROGRAM.get(s).exercises.isEmpty())
        assertTrue(s.allTypes().isEmpty())                                    // ни тренировок, ни еды, ни «частого»
        assertTrue(FoodModule.library(s).isEmpty())
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
