package by.zaberezh.forma.core

import by.zaberezh.forma.core.gym.DayPlan
import by.zaberezh.forma.core.gym.Exercise
import by.zaberezh.forma.core.gym.Program
import by.zaberezh.forma.core.gym.baseAdvice
import by.zaberezh.forma.core.gym.mainMuscle
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

    /** Прожить недели: каждую тренировку сделать ровно по плану. */
    private fun twoWeeks(split: String, sessions: Int = 3, program: Program = prog, weeks: Int = 2, minutes: Int = 90): List<DayPlan> {
        val s = MemoryStore(); PROGRAM.set(s, program)
        SETTINGS.set(s, Settings(split = split, sessionsPerWeek = sessions, sessionMin = minutes, schema = 99))
        val out = mutableListOf<DayPlan>()
        for (w in 0 until weeks) for (d in GymModule.week(Ctx(s, mon.plusWeeks(w.toLong()))).plan) {
            val plan = GymModule.planFor(Ctx(s, d), d)
            out += plan
            GymModule.savePlan(s, plan.copy(source = "edited"))
            WORKOUT.save(s, Workout(d.toString(), d.startMs() + 3600_000, sets = plan.items.flatMap { i -> List(i.sets) { SetLog(i.ex, 20.0, 10) } }),
                ts = d.startMs() + 3600_000)
        }
        return out
    }

    private fun mains(pl: DayPlan) = pl.items.map { mainMuscle(prog.ex(it.ex)!!) }
    private fun main(e: Exercise) = mainMuscle(e)
    private val legCurl = Exercise("leg_curl", "Сгибания ног сидя", mapOf("hams" to 1.0), repMin = 10, repMax = 15)
    private fun week(plans: List<DayPlan>, w: Int) = plans.filter { LocalDate.parse(it.date).let { d -> d >= mon.plusWeeks(w.toLong()) && d < mon.plusWeeks(w + 1L) } }
    /** Варианты движения мышцы в плане, по порядку. */
    private fun pats(pl: DayPlan, m: String, p: Program = prog) = pl.items.mapNotNull { p.ex(it.ex) }.filter { main(it) == m }.map(::pattern)
    private fun count(pl: DayPlan, m: String) = mains(pl).count { it == m }

    /** Мышца → дни недели, где она есть в плане. */
    private fun days(plans: List<DayPlan>, p: Program = prog): Map<String, List<LocalDate>> =
        plans.flatMap { pl -> pl.items.mapNotNull { p.ex(it.ex) }.map { mainMuscle(it) to LocalDate.parse(pl.date) } }
            .distinct().groupBy({ it.first }, { it.second })

    @Test fun everyMuscleOnceOrTwiceAWeekNeverTwoDaysInARow() {
        // правило: каждая мышца — хотя бы раз в неделю, два — если между тренировками есть день отдыха; не чаще двух
        val must = listOf("chest", "back", "side_delts", "biceps", "triceps", "quads", "hams", "forearms")
        for ((split, n) in listOf("ul" to 3, "ul" to 4, "ppl" to 3, "full" to 3)) {
            val plans = twoWeeks(split, n, weeks = 3)
            for (w in 0..2) {
                val d = days(week(plans, w))
                val what = "$split×$n неделя $w: $d"
                must.forEach { m -> assertTrue((d[m]?.size ?: 0) in 1..2, "$m: $what") }
                d.forEach { (m, l) -> assertTrue(l.sorted().zipWithNext().none { (x, y) -> y == x.plusDays(1) }, "$m два дня подряд: $what") }
            }
            plans.forEach { pl -> assertTrue(pl.items.sumOf { it.sets } in 12..26, "$split: подходов ${pl.items}") }
        }
        // два раза — когда успевает восстановиться: при 4 тренировках «Верх» и «Низ» — по два раза, мышцы — дважды
        for (w in 0..1) {
            val d = days(week(twoWeeks("ul", 4), w))
            listOf("chest", "back", "biceps", "triceps", "quads", "hams").forEach { assertEquals(2, d[it]?.size, "$it: $d") }
        }
        // «Верх–Низ–Верх»: руки в обоих «Верхах», в «Низ» им не нужно; «Низ–Верх–Низ»: «Верх» + один из «Низов»
        val ul = twoWeeks("ul", 3)
        val a = week(ul, 0); val b = week(ul, 1)
        assertEquals(listOf("upper", "lower", "upper"), a.map { it.day })
        assertTrue(a[1].items.none { mainMuscle(prog.ex(it.ex)!!) in setOf("biceps", "triceps") }, "${a[1].items}")
        assertEquals(2, days(b)["biceps"]?.size, "${b.map { it.items }}")
        assertEquals(1, days(b)["chest"]?.size)                  // крупные — только в свой день
        // разные варианты в разные дни: главный жим в двух «Верхах» недели — разный (горизонтальный и наклонный)
        val press = a.filter { it.day == "upper" }.map { pl -> pl.items.map { prog.ex(it.ex)!! }.first { main(it) == "chest" && by.zaberezh.forma.core.gym.isCompound(it) }.id }
        assertEquals(2, press.toSet().size, "жим по дням: $press")
    }

    @Test fun fullBodySpreadsEachMuscleOverTwoOfThreeDays() {
        val plans = twoWeeks("full")
        for (w in 0..1) {
            val wk = week(plans, w)
            val d = days(wk)
            listOf("chest", "back", "side_delts", "biceps", "triceps", "quads", "hams").forEach { assertEquals(2, d[it]?.size, "$it: $d") }
            wk.forEach { pl -> assertTrue(pl.items.size >= 5, "${pl.date}: ${mains(pl)}") }   // ни одного «пустого» дня
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
                // повтор варианта — только когда упражнений на мышцу больше, чем вариантов в базе
                val p = pats(pl, m)
                val avail = prog.exercises.filter { main(it) == m }.map(::pattern).toSet().size
                assertEquals(minOf(p.size, avail), p.toSet().size, "$split×$n ${pl.date}: $m — одинаковые варианты $p")
            }
        }
        // тяга дня — вертикальная + горизонтальная, а не две вертикальные (было: подтягивания + верхний блок)
        twoWeeks("ppl").filter { it.day == "pull" }.forEach { assertEquals(setOf("vertical", "horizontal"), pats(it, "back").toSet(), it.date) }
    }

    @Test fun weekCoversBothFunctionsAndRepeatsWeekToWeek() {
        val withCurl = prog.copy(exercises = prog.exercises + legCurl)
        val plans = twoWeeks("ul", 4, withCurl, weeks = 4)
        for (w in 0..3) {
            val wk = week(plans, w)
            // бицепс бедра: румынская тяга и сгибания ног — в разные «Низы» недели, а не одно и то же дважды
            assertEquals(setOf("hinge", "curl"), wk.flatMap { pats(it, "hams", withCurl) }.toSet(), "неделя $w")
            assertEquals(setOf("vertical", "horizontal"), wk.flatMap { pats(it, "back", withCurl) }.toSet(), "неделя $w")
            val upper = wk.filter { it.day == "upper" }
            assertEquals(2, upper.size)
            // разные «Верхи»: первым жимом — горизонтальный и наклонный
            assertEquals(setOf("flat", "incline"), upper.map { pats(it, "chest", withCurl).first() }.toSet())
        }
        // неделя к неделе — та же раскладка базовых (вес по ним растёт); первые недели вводят в ротацию то, что ещё не делал,
        // дальше раскладка устоялась
        fun base(pl: DayPlan) = pl.items.mapNotNull { withCurl.ex(it.ex) }.filter { main(it) in setOf("chest", "back", "quads", "hams") }.map { it.id }.toSet()
        val a = week(plans, 2); val b = week(plans, 3)
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

    @Test fun armsOnEveryUpperAndLegsOnEveryLowerAtAnyLength() {
        for (minutes in listOf(45, 60, 75, 90)) for (n in listOf(3, 4)) twoWeeks("ul", n, minutes = minutes).forEach { pl ->
            val m = mains(pl)
            val what = "$minutes мин ×$n ${pl.date} ${pl.day}: $m"
            if (pl.day == "upper") {
                // свой день рук: бицепс, трицепс, средняя дельта — прямой работой каждый раз (а не «бицепс ушёл в Низ»)
                assertTrue("biceps" in m && "triceps" in m && "side_delts" in m, what)
                assertTrue("back" in m && "chest" in m, what)
            } else assertTrue("quads" in m && "hams" in m, what)       // «Низ» без бицепса бедра — не «Низ»
        }
    }

    @Test fun pullMarkedWithBicepsDoesNotReplaceCurls() {
        // тяга, где бицепс отмечен основной мышцей раньше спины: упражнение всё равно на спину, и бицепсу оно не засчитывается
        val pulldown = prog.ex("lat_pulldown")!!.copy(muscles = linkedMapOf("biceps" to 1.0, "back" to 1.0, "rear_delts" to 0.5))
        assertEquals("back", mainMuscle(pulldown))
        assertEquals(null, by.zaberezh.forma.core.gym.Planner.credit(pulldown)["biceps"])
        val p = prog.copy(exercises = prog.exercises.map { if (it.id == "lat_pulldown") pulldown else it })
        twoWeeks("ul", program = p).filter { it.day == "upper" }.forEach { pl ->
            assertTrue(pl.items.any { p.ex(it.ex)?.let(::mainMuscle) == "biceps" }, "${pl.date}: ${pl.items.map { it.ex }}")
        }
    }
}
