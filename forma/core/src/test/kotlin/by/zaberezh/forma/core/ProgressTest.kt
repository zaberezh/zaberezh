package by.zaberezh.forma.core

import by.zaberezh.forma.core.body.saveWeight
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.PROGRAM
import by.zaberezh.forma.core.gym.SetLog
import by.zaberezh.forma.core.gym.WORKOUT
import by.zaberezh.forma.core.gym.Workout
import by.zaberezh.forma.core.gym.parseProgram
import by.zaberezh.forma.core.gym.progress
import by.zaberezh.forma.core.gym.setText
import by.zaberezh.forma.core.gym.targetText
import by.zaberezh.forma.core.store.MemoryStore
import by.zaberezh.forma.core.store.startMs
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Вкладка «Прогресс»: где сила растёт, где стоит, и что с этим делать. */
class ProgressTest {
    private val prog = parseProgram(javaClass.getResource("/test_program.json")!!.readText())
    private val today = LocalDate.of(2026, 10, 13)
    private val six = today.minusDays(41)

    private fun store(profile: Profile = Profile()) = MemoryStore().also {
        PROGRAM.set(it, prog); SETTINGS.set(it, Settings(profile = profile, schema = 99))
    }
    private fun log(s: MemoryStore, daysAgo: Long, vararg sets: SetLog) {
        val d = today.minusDays(daysAgo)
        WORKOUT.save(s, Workout(d.toString(), d.startMs() + 3600_000, sets = sets.toList()), ts = d.startMs() + 3600_000)
    }
    private fun three(ex: String, w: Double, r: Int) = Array(3) { SetLog(ex, w, r) }

    @Test fun growingAndStuckExercisesWithMuscles() {
        val s = store()
        // жим: двойная прогрессия идёт — повторы растут, потом вес; присед: шесть недель 100 × 8
        listOf(60.0 to 8, 60.0 to 9, 60.0 to 10, 62.5 to 8, 62.5 to 9, 62.5 to 10).forEachIndexed { i, (w, r) ->
            log(s, 36L - i * 7, *three("bench", w, r), *three("squat", 100.0, 8))
        }
        val pr = GymModule.progress(Ctx(s, today), six)
        val bench = pr.items.first { it.ex.id == "bench" }
        val squat = pr.items.first { it.ex.id == "squat" }
        assertEquals("растёт", bench.trend.status)
        assertEquals("стоит", squat.trend.status)
        assertEquals(6, bench.points.size)
        assertTrue(bench.change!! > 5, "${bench.change}")
        assertEquals("60 кг × 8", setText(bench.ex, bench.points.first().best))
        assertEquals("65 кг × 6, 6, 6", targetText(bench.next))  // 62.5 × 10 во всех подходах → +шаг и снова с низа диапазона
        assertTrue(bench.advice.isEmpty())
        assertEquals(listOf("chest"), pr.going)
        assertEquals(listOf("quads"), pr.stuck)
        // присед стоит: мало подходов на квадрицепс (3 в неделю при норме 6–12) и что попробовать — по его диапазону
        assertTrue(squat.advice.any { "Мало подходов: квадрицепс — 3 в неделю" in it }, "${squat.advice}")
        assertTrue(squat.advice.any { "в запасе не больше 2" in it && "10–15" in it }, "${squat.advice}")
        assertTrue(pr.notes.isEmpty(), "${pr.notes}")            // два упражнения — ещё не «застой везде»
    }

    @Test fun stallAlmostEverywhereIsOneAdviceOnTop() {
        val s = store()
        for (i in 0..4) log(s, 29L - i * 7, *three("bench", 60.0 + i * 2.5, 8), *three("squat", 100.0, 8),
            *three("row", 70.0, 8), *three("ohp", 40.0, 8))
        val pr = GymModule.progress(Ctx(s, today), six)
        assertTrue(pr.notes.single().startsWith("Не идёт в 3 из 4 упражнений"), "${pr.notes}")
        // общий совет — наверху, у упражнений — без повтора
        pr.items.filter { it.trend.status == "стоит" }.forEach { assertTrue(it.advice.none { a -> "в запасе" in a }, "${it.advice}") }
    }

    @Test fun exerciseWithoutWeightIsMeasuredInReps() {
        val s = store()
        listOf(8, 10, 12, 14).forEachIndexed { i, r -> log(s, 22L - i * 7, *three("hanging_raise", 0.0, r)) }
        val raise = GymModule.progress(Ctx(s, today), six).items.single()
        assertTrue(raise.reps)
        assertEquals("растёт", raise.trend.status)
        assertEquals("14 повт.", setText(raise.ex, raise.points.last().best))
        assertEquals("15, 15 повт.", targetText(raise.next))
        // и в базе упражнений: раньше без веса сила была 0 и выходило «падает»
        assertEquals("растёт", GymModule.trend(s, prog.ex("hanging_raise")!!, today).status)
    }

    @Test fun bodyweightSetsInPlainWords() {
        val s = store()
        log(s, 15, SetLog("pullup", 0.0, 8)); log(s, 8, SetLog("pullup", 0.0, 10)); log(s, 1, SetLog("pullup", 5.0, 8))
        val pull = GymModule.progress(Ctx(s, today), six).items.single()
        assertEquals(false, pull.reps)
        assertEquals("свой вес × 8", setText(pull.ex, pull.points.first().best))
        assertEquals("+5 кг × 8", setText(pull.ex, pull.points.last().best))
        assertEquals("+5 кг × 9, 9, 9", targetText(pull.next))
    }

    @Test fun periodScalesTheChartButTheVerdictIsAlwaysSixWeeks() {
        val s = store()
        for (d in listOf(100L, 93L, 86L)) log(s, d, *three("curl", 30.0, 10))
        assertTrue(GymModule.progress(Ctx(s, today), six).items.isEmpty())
        val curl = GymModule.progress(Ctx(s, today), null).items.single()
        assertEquals(3, curl.points.size)
        assertEquals("нет данных", curl.trend.status)            // за последние 6 недель не делал
        assertNull(curl.advice.firstOrNull())
    }

    @Test fun rareExerciseAndCutting() {
        val s = store(Profile(targetKg = 70.0))
        saveWeight(s, today.minusDays(1), 80.0)
        for (d in listOf(29L, 15L, 1L)) log(s, d, *three("row", 70.0, 8))
        val pr = GymModule.progress(Ctx(s, today), six)
        val row = pr.items.single()
        assertEquals("стоит", row.trend.status)
        assertTrue(row.advice.any { "раз в ~14 дн." in it }, "${row.advice}")
        assertTrue(pr.notes.any { "худеешь" in it }, "${pr.notes}")
    }
}
