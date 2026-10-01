package by.zaberezh.forma.core

import by.zaberezh.forma.core.body.WEIGHT
import by.zaberezh.forma.core.body.Weight
import by.zaberezh.forma.core.body.saveWeight
import by.zaberezh.forma.core.body.weightDays
import by.zaberezh.forma.core.body.weightOn
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
}
