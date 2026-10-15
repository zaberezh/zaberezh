package by.zaberezh.forma.core

import by.zaberezh.forma.core.gym.DayPlan
import by.zaberezh.forma.core.gym.Exercise
import by.zaberezh.forma.core.gym.ExerciseInput
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.PROGRAM
import by.zaberezh.forma.core.gym.Planner
import by.zaberezh.forma.core.gym.Program
import by.zaberezh.forma.core.gym.SetLog
import by.zaberezh.forma.core.gym.WORKOUT
import by.zaberezh.forma.core.gym.Workout
import by.zaberezh.forma.core.gym.guessMuscles
import by.zaberezh.forma.core.gym.guessStep
import by.zaberezh.forma.core.gym.mainMuscle
import by.zaberezh.forma.core.gym.nextTarget
import by.zaberezh.forma.core.gym.pattern
import by.zaberezh.forma.core.gym.reguessed
import by.zaberezh.forma.core.gym.upsert
import by.zaberezh.forma.core.store.MemoryStore
import by.zaberezh.forma.core.store.startMs
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Логика упражнений целиком — как у человека, который сам вбил свои упражнения названиями. */
class ExerciseLogicTest {
    private fun main(name: String) = guessMuscles(name).let { g -> mainMuscle(Exercise("x", name, g)) }

    @Test fun musclesByNameWhereOneWordMisleads() {
        assertEquals("triceps", main("Французский жим лёжа"))          // не грудь
        assertEquals("triceps", main("Французский жим сидя"))          // не плечи
        assertEquals("triceps", main("Жим узким хватом"))
        assertEquals("triceps", main("Обратные отжимания от скамьи"))
        assertEquals("triceps", main("Кикбеки"))
        assertEquals("forearms", main("Разгибания запястий"))          // не трицепс
        assertEquals("forearms", main("Сгибания запястий со штангой"))  // не бицепс
        assertEquals("side_delts", main("Тяга штанги к подбородку"))    // не спина
        assertEquals("front_delts", main("Махи перед собой"))
        assertEquals("front_delts", main("Подъём гантелей перед собой")) // не бицепс
        assertEquals("front_delts", main("Жим Арнольда"))
        assertEquals("chest", main("Жим от груди сидя в тренажёре"))    // не плечи
        assertEquals("front_delts", main("Жим штанги от груди стоя"))
        assertEquals("chest", main("Разведение гантелей лёжа"))
        assertEquals("chest", main("Пек-дек"))
        assertEquals("biceps", main("Сгибания рук в кроссовере"))      // не сведения
        assertEquals("abs", main("Скручивания на блоке"))
        assertEquals("back", main("Пуловер на блоке"))
        assertEquals("glutes", main("Хип-траст"))
        assertEquals(1.0, guessMuscles("Болгарские сплит-приседания")["glutes"])
        assertEquals(0.25, guessMuscles("Фронтальные приседания")["abs"])
        // вариант движения у трицепса: жим узким хватом — жим, французский — лёжа
        fun pat(n: String) = pattern(Exercise("x", n, guessMuscles(n)))
        assertEquals("press", pat("Жим узким хватом лёжа"))
        assertEquals("lying", pat("Французский жим лёжа"))
    }

    @Test fun realisticWeightSteps() {
        assertEquals(2.0, guessStep("Молотки"))                          // гантели — через 2 кг, а не 13 кг после 12
        assertEquals(2.5, guessStep("Подъём штанги на бицепс"))
        assertEquals(2.5, guessStep("Разгибания на трицепс на блоке"))
        assertEquals(5.0, guessStep("Тяга верхнего блока"))
        assertEquals(1.0, guessStep("Махи гантелями в стороны"))
        assertEquals(0.0, guessStep("Планка"))
        assertEquals(0.0, guessStep("Подъём ног в висе"))
    }

    @Test fun oldAutoGuessesAreFixedHandPickedStay() {
        // так «Французский жим» сохранялся раньше: грудь, как базовое (в запасе 2, отдых 150 с), шаг 1 кг
        val old = Exercise("fr", "Французский жим лёжа", mapOf("chest" to 1.0, "triceps" to 0.5, "front_delts" to 0.5), rir = 2, restSec = 150, step = 1.0)
        val mine = Exercise("my", "Французский жим лёжа", mapOf("triceps" to 1.0, "chest" to 1.0), step = 1.0)   // выбрал сам
        val p = Program("p", 2, listOf(old, mine), emptyList()).reguessed()
        assertEquals(mapOf("triceps" to 1.0), p.ex("fr")!!.muscles)
        assertEquals(1, p.ex("fr")!!.rir); assertEquals(90, p.ex("fr")!!.restSec); assertEquals(2.5, p.ex("fr")!!.step)
        assertEquals(mine.muscles, p.ex("my")!!.muscles)
    }

    private val bench = Exercise("b", "Жим", mapOf("chest" to 1.0), sets = 3, repMin = 8, repMax = 12, step = 2.5)
    private fun s(w: Double, vararg r: Int) = r.map { SetLog("b", w, it) }

    @Test fun progressionIsNotFooledBySetCount() {
        // 4 подхода по 10, потом 2 по 10, потом 2 по 11 — это рост, а не «3 тренировки без прироста»
        val t = nextTarget(bench, listOf(s(60.0, 10, 10, 10, 10), s(60.0, 10, 10), s(60.0, 11, 11)))
        assertEquals(60.0, t.weight); assertEquals(listOf(12, 12, 12), t.reps)
        // настоящий застой посреди диапазона — сброс −10%
        assertEquals(55.0, nextTarget(bench, List(3) { s(60.0, 9, 8, 8) }).weight)
        // застой у самого верха (11–12 при 12) — вес уже по силам: +шаг, а не вниз
        val top = nextTarget(bench, List(3) { s(60.0, 12, 11, 11) })
        assertEquals(62.5, top.weight); assertEquals(listOf(8, 8, 8), top.reps)
        // сегодня в плане 4 подхода, а в прошлый раз было 2 — оба на верхе диапазона: вес растёт
        assertEquals(62.5, nextTarget(bench.copy(sets = 4), listOf(s(60.0, 12, 12))).weight)
        // долгий перерыв — повторить прошлый результат
        val back = nextTarget(bench, listOf(s(60.0, 11, 10, 10)), restDays = 30)
        assertEquals(60.0, back.weight); assertEquals(listOf(11, 10, 10), back.reps)
    }

    @Test fun progressionWithoutWeight() {
        // подтягивания своим весом: застой — сбрасывать некуда, а не «0 кг × 10» после 8
        val pull = Exercise("p", "Подтягивания", mapOf("back" to 1.0), repMin = 6, repMax = 12, step = 2.5, bw = 1.0)
        val st = nextTarget(pull, List(3) { listOf(SetLog("p", 0.0, 8), SetLog("p", 0.0, 7)) })
        assertEquals(0.0, st.weight); assertEquals(listOf(8, 7, 7), st.reps)
        // планка без шага веса: растут повторы (секунды) и выше диапазона
        val plank = Exercise("k", "Подъём ног в висе", mapOf("abs" to 1.0), sets = 2, repMin = 8, repMax = 15, step = 0.0)
        assertEquals(listOf(16, 16), nextTarget(plank, listOf(listOf(SetLog("k", 0.0, 15), SetLog("k", 0.0, 15)))).reps)
    }

    /** База как у человека: названия, вес, повторы — мышцы угаданы по названию. */
    private fun base(): Program {
        var p = Program("Моя", 2, emptyList(), emptyList())
        listOf(
            "Жим штанги лёжа" to "6-10", "Жим гантелей на наклонной" to "8-12", "Сведения в кроссовере" to "10-15",
            "Подтягивания" to "6-10", "Тяга горизонтального блока" to "8-12", "Тяга штанги в наклоне" to "8-10", "Шраги" to "10-15",
            "Жим гантелей сидя" to "8-12", "Махи гантелями в стороны" to "12-15", "Тяга каната к лицу" to "12-15",
            "Подъём штанги на бицепс" to "8-12", "Молотки" to "10-12", "Французский жим лёжа" to "10-12",
            "Разгибания на трицепс на блоке" to "10-15", "Приседания со штангой" to "6-10", "Жим ногами" to "10-15",
            "Румынская тяга" to "8-10", "Сгибания ног лёжа" to "10-15", "Подъём на носки стоя" to "12-15",
            "Скручивания" to "12-20", "Сгибания запястий" to "12-20",
        ).forEach { (n, r) -> p = p.upsert(null, ExerciseInput(n, 40.0, r, 3, null, n.startsWith("Подтяг"), null)) }
        return p
    }

    /** Прожить недели по плану приложения. */
    private fun live(split: String, sessions: Int, minutes: Int, weeks: Int = 2): Pair<Program, List<DayPlan>> {
        val s = MemoryStore(); val p = base(); PROGRAM.set(s, p)
        SETTINGS.set(s, Settings(split = split, sessionsPerWeek = sessions, sessionMin = minutes, schema = 99))
        val mon = LocalDate.of(2026, 10, 5)
        val out = mutableListOf<DayPlan>()
        for (w in 0 until weeks) for (d in GymModule.week(Ctx(s, mon.plusWeeks(w.toLong()))).plan) {
            val plan = GymModule.planFor(Ctx(s, d), d)
            out += plan
            GymModule.savePlan(s, plan)
            WORKOUT.save(s, Workout(d.toString(), d.startMs() + 3600_000, sets = plan.items.flatMap { i -> List(i.sets) { SetLog(i.ex, 40.0, 10) } }),
                ts = d.startMs() + 3600_000)
        }
        return p to out
    }

    @Test fun backOnEveryUpperDayAndShrugsDoNotReplaceRows() {
        // румынская тяга в «Низ» задевает спину, но тренировкой спины не считается: во втором «Верхе» спина есть
        val (p, plans) = live("ul", 3, 90)
        plans.filter { it.day == "upper" }.forEach { pl ->
            val back = pl.items.map { p.ex(it.ex)!! }.filter { mainMuscle(it) == "back" }
            assertTrue(back.isNotEmpty(), "${pl.date}: ${pl.items}")
            assertTrue(back.none { "Шраги" in it.name }, "шраги вместо тяги ${pl.date}: ${back.map { it.name }}")
        }
    }

    @Test fun compoundGetsAtLeastTheSetsOfIsolation() {
        val (p, plans) = live("ul", 3, 90)
        plans.forEach { pl ->
            pl.items.groupBy { mainMuscle(p.ex(it.ex)!!) }.values.filter { it.size > 1 }.forEach { l ->
                val (comp, iso) = l.partition { by.zaberezh.forma.core.gym.isCompound(p.ex(it.ex)!!) }
                assertTrue(iso.isEmpty() || comp.isEmpty() || comp.minOf { it.sets } >= iso.maxOf { it.sets },
                    "${pl.date}: ${l.map { p.ex(it.ex)!!.name to it.sets }}")
            }
        }
    }

    @Test fun pushDayStartsWithPressNotCalves() {
        val (p, plans) = live("ppl", 3, 90)
        plans.forEach { pl -> assertTrue(mainMuscle(p.ex(pl.items.first().ex)!!) !in setOf("calves", "forearms", "abs"), "${pl.date} ${pl.day}: ${pl.items}") }
    }

    @Test fun secondLowerKeepsLegsAfterHeavyFirst() {
        // 4 тренировки: первый «Низ» добрал ногам с запасом — во втором «Низе» квадрицепс всё равно есть (частота 2)
        val (p, plans) = live("ul", 4, 75)
        plans.filter { it.day == "lower" }.forEach { pl ->
            assertTrue(pl.items.any { mainMuscle(p.ex(it.ex)!!) == "quads" }, "${pl.date}: ${pl.items.map { p.ex(it.ex)!!.name }}")
            assertTrue(pl.items.sumOf { it.sets } >= 14, "${pl.date}: ${pl.items}")
        }
        // руки — в обоих «Верхах» (двух по 75 мин не хватает на минимумы всех мышц верха, но и рукам, и спине — дважды)
        plans.filter { it.day == "upper" }.forEach { pl ->
            val c = Planner.planCredit(p, pl)
            listOf("back", "chest", "triceps", "biceps").forEach { m -> assertTrue((c[m] ?: 0.0) >= 2.0, "${pl.date} $m: $c") }
        }
    }
}
