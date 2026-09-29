package by.zaberezh.forma.core

import by.zaberezh.forma.core.gym.ExerciseInput
import by.zaberezh.forma.core.gym.defaultProgram
import by.zaberezh.forma.core.gym.guessMuscles
import by.zaberezh.forma.core.gym.nextTarget
import by.zaberezh.forma.core.gym.parseReps
import by.zaberezh.forma.core.gym.remove
import by.zaberezh.forma.core.gym.upsert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditTest {
    @Test fun reps() {
        assertEquals(8 to 12, parseReps("8-12")); assertEquals(8 to 12, parseReps("12–8"))
        assertEquals(10 to 14, parseReps("10")); assertEquals(5 to 8, parseReps("5"))
        assertEquals(null, parseReps("много"))
    }

    @Test fun guesses() {
        assertEquals(1.0, guessMuscles("Жим штанги лёжа")["chest"])
        assertEquals(1.0, guessMuscles("Жим гантелей сидя")["front_delts"])
        assertEquals(1.0, guessMuscles("Тяга верхнего блока")["back"])
        assertEquals(1.0, guessMuscles("Махи гантелями в стороны")["side_delts"])
        assertEquals(1.0, guessMuscles("Жим ногами")["quads"])
        assertEquals(1.0, guessMuscles("Молотки")["biceps"])
        assertTrue(guessMuscles("что-то странное").isEmpty())
    }

    @Test fun addEditRemove() {
        var p = defaultProgram()
        p = p.upsert(null, ExerciseInput("Жим штанги лёжа", 60.0, "8", 3, null, false, null))
        val ex = p.exercises.single()
        assertEquals(8 to 12, ex.repMin to ex.repMax); assertEquals(2.5, ex.step)
        val t = nextTarget(ex, emptyList())
        assertEquals(60.0, t.weight); assertEquals(listOf(8, 8, 8), t.reps)

        p = p.upsert(ex.id, ExerciseInput("Жим штанги лёжа", 62.5, "6-10", 4, null, false, null))
        assertEquals(1, p.exercises.size); assertEquals(4, p.exercises[0].sets)

        p = p.upsert(null, ExerciseInput("Подтягивания", 0.0, "5-10", 3, null, true, null))
        assertEquals(1.0, p.exercises.last().bw)

        assertFailsWith<IllegalArgumentException> { p.upsert(null, ExerciseInput("Непонятное", 10.0, "10", 3, null, false, null)) }
        assertEquals(mapOf("abs" to 1.0), p.upsert(null, ExerciseInput("Непонятное", 10.0, "10", 3, "abs", false, null)).exercises.last().muscles)

        p = p.remove(ex.id)
        assertEquals(1, p.exercises.size)
    }
}
