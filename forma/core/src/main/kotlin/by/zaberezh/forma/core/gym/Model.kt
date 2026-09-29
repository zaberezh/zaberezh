package by.zaberezh.forma.core.gym

import by.zaberezh.forma.core.store.JSON
import by.zaberezh.forma.core.store.Kind
import by.zaberezh.forma.core.store.Pref
import kotlinx.serialization.Serializable

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val muscles: Map<String, Double>,   // мышца -> вклад подхода (1 прямая, 0.5 косвенная)
    val sets: Int = 3,
    val repMin: Int = 8,
    val repMax: Int = 12,
    val step: Double = 2.5,             // шаг прибавки веса
    val bw: Double = 0.0,               // доля веса тела в нагрузке (подтягивания/брусья = 1.0)
    val rir: Int = 2,                   // сколько повторов оставлять в запасе
    val restSec: Int = 120,
    val note: String = "",
    val startWeight: Double? = null,    // вес на старте (пока нет истории)
)

@Serializable
data class TrainingDay(val id: String, val name: String, val exercises: List<String>)

@Serializable
data class Program(
    val name: String,
    val version: Int = 1,
    val exercises: List<Exercise>,
    val days: List<TrainingDay>,
    val volume: Map<String, List<Int>> = emptyMap(), // мышца -> [мин, макс] подходов в неделю
) {
    fun ex(id: String) = exercises.firstOrNull { it.id == id }
    fun day(id: String) = days.firstOrNull { it.id == id }
}

@Serializable
data class SetLog(val ex: String, val w: Double, val r: Int, val rir: Int? = null)

@Serializable
data class Workout(
    val day: String,
    val start: Long,
    val end: Long? = null,
    val sets: List<SetLog> = emptyList(),
    val gym: String? = null,
    val note: String = "",
)

@Serializable
data class Visit(val gym: String, val start: Long, val end: Long) {
    val minutes: Long get() = (end - start) / 60_000
}

val WORKOUT = Kind("gym.workout", Workout.serializer())
val VISIT = Kind("gym.visit", Visit.serializer())
val PROGRAM = Pref("gym.program", Program.serializer()) { defaultProgram() }

val MUSCLES = linkedMapOf(
    "chest" to "грудь", "back" to "спина (ширина/толщина)", "side_delts" to "средняя дельта",
    "front_delts" to "передняя дельта", "rear_delts" to "задняя дельта", "biceps" to "бицепс",
    "triceps" to "трицепс", "quads" to "квадрицепс", "hams" to "бицепс бедра", "glutes" to "ягодицы",
    "calves" to "икры", "abs" to "пресс", "forearms" to "предплечья",
)

fun defaultProgram(): Program =
    JSON.decodeFromString(Program.serializer(), Program::class.java.getResource("/default_program.json")!!.readText())

fun parseProgram(text: String): Program = JSON.decodeFromString(Program.serializer(), text).also { p ->
    require(p.days.isNotEmpty()) { "нет дней" }
    p.days.flatMap { it.exercises }.forEach { id -> requireNotNull(p.ex(id)) { "упражнение '$id' не описано" } }
}
