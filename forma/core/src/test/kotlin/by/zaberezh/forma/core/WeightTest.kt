package by.zaberezh.forma.core

import by.zaberezh.forma.core.body.WEIGHT
import by.zaberezh.forma.core.body.Weight
import by.zaberezh.forma.core.body.saveWeight
import by.zaberezh.forma.core.body.weightDays
import by.zaberezh.forma.core.body.weightOn
import by.zaberezh.forma.core.body.weightGoal
import by.zaberezh.forma.core.body.GAIN_PACE
import by.zaberezh.forma.core.body.LOSS_PACE
import by.zaberezh.forma.core.store.MemoryStore
import by.zaberezh.forma.core.store.startMs
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test fun targetWeightPicksGainOrLoss() {
        assertEquals(0.1, weightGoal(Profile(gainKgPerWeek = 0.1), 70.0).rate)                  // цели нет — темп из настроек
        val up = weightGoal(Profile(targetKg = 75.0), 70.0)
        assertEquals(GAIN_PACE, up.rate); assertEquals(20, up.weeks)                               // набор: 5 кг / 0.25
        val down = weightGoal(Profile(targetKg = 65.0), 70.0)
        assertEquals(-LOSS_PACE, down.rate); assertEquals(10, down.weeks)                          // похудение: 5 кг / 0.5
        assertEquals(0.0, weightGoal(Profile(targetKg = 70.3), 70.0).rate)                       // у цели — держим
        assertEquals("75 кг · +0.25 кг/нед · ≈20 нед", up.label)

        val s = MemoryStore(); val d = LocalDate.of(2026, 10, 1)
        saveWeight(s, d, 80.0)
        assertEquals(-LOSS_PACE, weightGoal(s, Profile(targetKg = 75.0), d).rate)                // текущий вес — из записей
    }
}
