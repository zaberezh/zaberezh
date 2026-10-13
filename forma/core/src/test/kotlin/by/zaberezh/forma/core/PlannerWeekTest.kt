package by.zaberezh.forma.core

import by.zaberezh.forma.core.gym.DayPlan
import by.zaberezh.forma.core.gym.Exercise
import by.zaberezh.forma.core.gym.Program
import by.zaberezh.forma.core.gym.baseAdvice
import by.zaberezh.forma.core.gym.pattern
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
    private fun twoWeeks(split: String, sessions: Int = 3, program: Program = prog): List<DayPlan> {
        val s = MemoryStore(); PROGRAM.set(s, program); SETTINGS.set(s, Settings(split = split, sessionsPerWeek = sessions, schema = 99))
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
    private fun main(e: Exercise) = e.muscles.maxBy { it.value }.key
    private val legCurl = Exercise("leg_curl", "Сгибания ног сидя", mapOf("hams" to 1.0), repMin = 10, repMax = 15)
    private fun week(plans: List<DayPlan>, w: Int) = plans.filter { LocalDate.parse(it.date).let { d -> d >= mon.plusWeeks(w.toLong()) && d < mon.plusWeeks(w + 1L) } }
    /** Варианты движения мышцы в плане, по порядку. */
    private fun pats(pl: DayPlan, m: String, p: Program = prog) = pl.items.mapNotNull { p.ex(it.ex) }.filter { main(it) == m }.map(::pattern)
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

    @Test fun patternsOfCommonExercises() {
        fun pat(name: String, vararg m: Pair<String, Double>) = pattern(Exercise("x", name, mapOf(*m)))
        assertEquals("vertical", pat("Подтягивания широким хватом", "back" to 1.0, "biceps" to 0.5))
        assertEquals("vertical", pat("Тяга верхнего блока к груди", "back" to 1.0))
        assertEquals("horizontal", pat("Тяга гантели одной рукой", "back" to 1.0))
        assertEquals("horizontal", pat("Тяга горизонтального блока", "back" to 1.0))
        assertEquals("incline", pat("Жим гантелей на наклонной 30°", "chest" to 1.0, "triceps" to 0.5))
        assertEquals("flat", pat("Жим штанги лёжа", "chest" to 1.0, "triceps" to 0.5))
        assertEquals("dips", pat("Отжимания на брусьях", "chest" to 1.0, "triceps" to 1.0))
        assertEquals("fly", pat("Сведения в кроссовере", "chest" to 1.0))
        assertEquals("hinge", pat("Румынская тяга", "hams" to 1.0, "glutes" to 0.5))
        assertEquals("curl", pat("Сгибания ног лёжа", "hams" to 1.0))
        assertEquals("overhead", pat("Французский жим сидя", "triceps" to 1.0))
        assertEquals("lying", pat("Французский жим лёжа", "triceps" to 1.0))
        assertEquals("overhead", pat("Разгибания из-за головы на блоке", "triceps" to 1.0))
        assertEquals("pushdown", pat("Разгибания на блоке канатом", "triceps" to 1.0))
        assertEquals("hammer", pat("Молотки с гантелями", "biceps" to 1.0))
        assertEquals("stretch", pat("Сгибания на наклонной скамье", "biceps" to 1.0))
        assertEquals("seated", pat("Подъём на носки сидя", "calves" to 1.0))
    }

    @Test fun twoExercisesOfOneMuscleAreDifferentMovements() {
        for ((split, n) in listOf("ul" to 3, "ul" to 4, "ppl" to 3, "full" to 3)) twoWeeks(split, n).forEach { pl ->
            for (m in listOf("back", "chest", "triceps", "biceps")) {
                val p = pats(pl, m)
                assertEquals(p.size, p.toSet().size, "$split×$n ${pl.date}: $m — одинаковые варианты $p")
            }
        }
        // тяга дня — вертикальная + горизонтальная, а не две вертикальные (было: подтягивания + верхний блок)
        twoWeeks("ppl").filter { it.day == "pull" }.forEach { assertEquals(setOf("vertical", "horizontal"), pats(it, "back").toSet(), it.date) }
    }

    @Test fun weekCoversBothFunctionsAndRepeatsWeekToWeek() {
        val withCurl = prog.copy(exercises = prog.exercises + legCurl)
        val plans = twoWeeks("ul", 4, withCurl)
        for (w in 0..1) {
            val wk = week(plans, w)
            // бицепс бедра: румынская тяга и сгибания ног — в разные «Низы» недели, а не одно и то же дважды
            assertEquals(setOf("hinge", "curl"), wk.flatMap { pats(it, "hams", withCurl) }.toSet(), "неделя $w")
            assertEquals(setOf("vertical", "horizontal"), wk.flatMap { pats(it, "back", withCurl) }.toSet(), "неделя $w")
            val upper = wk.filter { it.day == "upper" }
            assertEquals(2, upper.size)
            // разные «Верхи»: первым жимом — горизонтальный и наклонный
            assertEquals(setOf("flat", "incline"), upper.map { pats(it, "chest", withCurl).first() }.toSet())
        }
        // неделя к неделе — та же раскладка базовых (вес по ним растёт)
        fun base(pl: DayPlan) = pl.items.mapNotNull { withCurl.ex(it.ex) }.filter { main(it) in setOf("chest", "back", "quads", "hams") }.map { it.id }.toSet()
        val a = week(plans, 0); val b = week(plans, 1)
        a.indices.forEach { i -> assertEquals(base(a[i]), base(b[i]), "день $i: ${a[i].date} / ${b[i].date}") }
    }

    @Test fun upperDaysAlternateWhatGoesFirst() {
        val upper = twoWeeks("ul", 4).filter { it.day == "upper" }
        val first = upper.map { main(prog.ex(it.items.first().ex)!!) }
        // свежими приходят то грудь (жимы первыми), то спина (тяги первыми)
        assertEquals(listOf("chest", "back", "chest", "back"), first, "${upper.map { it.items.first().ex }}")
        assertEquals(listOf(null, "pull", null, "pull"), upper.map { it.lead })
    }

    @Test fun adviceForMissingMovements() {
        val a = baseAdvice(prog)
        assertTrue(a.any { "сгибаний ног" in it }, "$a")                 // в тестовой базе только румынская тяга
        assertTrue(a.none { "вертикальной" in it || "горизонтальной" in it }, "$a")
        val poor = Program("p", exercises = listOf(prog.ex("bench")!!, prog.ex("lat_pulldown")!!), days = emptyList())
        val b = baseAdvice(poor)
        assertTrue(b.any { "горизонтальной тяги" in it } && b.any { "наклонной" in it } && b.any { "Одно упражнение" in it }, "$b")
    }
}
