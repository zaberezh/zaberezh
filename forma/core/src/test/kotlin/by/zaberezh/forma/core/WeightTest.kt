package by.zaberezh.forma.core

import by.zaberezh.forma.core.body.WEIGHT
import by.zaberezh.forma.core.body.Weight
import by.zaberezh.forma.core.body.saveWeight
import by.zaberezh.forma.core.body.trendWeight
import by.zaberezh.forma.core.body.weightDays
import by.zaberezh.forma.core.body.weightOn
import by.zaberezh.forma.core.body.weightGoal
import by.zaberezh.forma.core.body.GAIN_PACE
import by.zaberezh.forma.core.body.LOSS_PACE
import by.zaberezh.forma.core.body.planNote
import by.zaberezh.forma.core.body.withGoal
import by.zaberezh.forma.core.store.MemoryStore
import by.zaberezh.forma.core.store.startMs
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightTest {
    @Test fun oneWeightPerDayEditable() {
        val s = MemoryStore()
        val d = LocalDate.of(2026, 10, 1)
        // старые дубли за день (как было до исправления)
        WEIGHT.save(s, Weight(69.0), ts = d.startMs() + 1000); WEIGHT.save(s, Weight(69.0), ts = d.startMs() + 2000)
        WEIGHT.save(s, Weight(70.5), ts = d.minusDays(2).startMs() + 1000)
        saveWeight(s, d, 69.4, now = d.startMs() + 3000)
        saveWeight(s, d, 69.2, now = d.startMs() + 4000)                 // правка, а не новая запись
        assertEquals(1, WEIGHT.all(s, d.startMs(), d.plusDays(1).startMs() - 1).size)
        assertEquals(69.2, weightOn(s, d))
        assertEquals(listOf(d to 69.2, d.minusDays(2) to 70.5), weightDays(s, 10))
    }

    @Test fun trendFollowsTheNewWeightAfterABreak() {
        val s = MemoryStore()
        val d = LocalDate.of(2026, 10, 1)
        // ежедневно: шум ±0,5 кг сглаживается — тренд около среднего
        for (i in 0..13) saveWeight(s, d.minusDays(13L - i), if (i % 2 == 0) 70.5 else 69.5, now = d.minusDays(13L - i).startMs() + 1000)
        assertEquals(70.0, trendWeight(s, d)!!, 0.4)
        // месяц без весов, потом 73 — старые 70 почти не тянут назад (раньше тренд показывал ~70,3)
        val later = d.plusDays(30)
        saveWeight(s, later, 73.0, now = later.startMs() + 1000)
        assertTrue(trendWeight(s, later)!! > 72.8, "${trendWeight(s, later)}")
    }

    @Test fun targetWeightPicksGainOrLoss() {
        assertEquals(0.0, weightGoal(Profile(gainKgPerWeek = 0.1), 70.0).rate)                  // цели нет — держим вес
        val up = weightGoal(Profile(targetKg = 75.0), 70.0)
        assertEquals(GAIN_PACE, up.rate); assertEquals(20, up.weeks)                               // набор: 5 кг / 0.25
        val down = weightGoal(Profile(targetKg = 65.0), 70.0)
        assertEquals(-LOSS_PACE, down.rate); assertEquals(10, down.weeks)                          // похудение: 5 кг / 0.5
        assertEquals(0.0, weightGoal(Profile(targetKg = 70.3), 70.0).rate)                       // у цели — держим
        assertEquals("70 → 75 кг · набор", up.label)                                              // старта нет — текущий вес

        val s = MemoryStore(); val d = LocalDate.of(2026, 10, 1)
        saveWeight(s, d, 80.0)
        assertEquals(-LOSS_PACE, weightGoal(s, Profile(targetKg = 75.0), d).rate)                // текущий вес — из записей
    }

    @Test fun startWeightGivesProgressAndPlan() {
        val p = Profile(targetKg = 75.0, startKg = 70.0, startDate = "2026-09-01")
        val g = weightGoal(p, 71.0)
        assertEquals(1.0, g.doneKg!!, 1e-9); assertEquals(5.0, g.totalKg!!, 1e-9); assertEquals(0.2, g.progress!!, 1e-9)
        val day = LocalDate.of(2026, 10, 1)
        assertEquals(70.0 + GAIN_PACE * 30 / 7.0, g.planned(day)!!, 1e-9)                        // по плану ~71.07
        assertEquals("идёшь по плану", planNote(g.vsPlan(day)!!))
        assertEquals("отстаёшь от плана на 1.6 кг", planNote(weightGoal(p, 70.0).vsPlan(LocalDate.of(2026, 10, 15))!!))
        assertEquals(75.0, g.planned(LocalDate.of(2027, 9, 1)))                                   // дальше цели план не идёт
        // похудение: прогресс — в обратную сторону
        val cut = weightGoal(Profile(targetKg = 70.0, startKg = 80.0, startDate = "2026-09-01"), 77.5)
        assertEquals(0.25, cut.progress!!, 1e-9); assertEquals(-LOSS_PACE, cut.rate)

        // старт пустой — текущий вес и сегодняшняя дата; сменил только цель — путь не начинается заново
        val set = Profile().withGoal(78.0, null, 72.4, day)
        assertEquals(72.4, set.startKg); assertEquals("2026-10-01", set.startDate)
        val moved = set.withGoal(80.0, 72.4, 74.0, day.plusDays(20))
        assertEquals("2026-10-01", moved.startDate); assertEquals(80.0, moved.targetKg)
        assertEquals("2026-10-21", set.withGoal(80.0, 74.0, 74.0, day.plusDays(20)).startDate)  // новый старт — новый отсчёт
        assertEquals(null, set.withGoal(null, null, 74.0, day).targetKg)
    }
}
