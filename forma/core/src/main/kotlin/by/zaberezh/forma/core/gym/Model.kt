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

val WORKOUT = Kind("gym.workout", Workout.serializer())
val PROGRAM = Pref("gym.program", Program.serializer()) { defaultProgram() }

val MUSCLES = linkedMapOf(
    "chest" to "грудь", "back" to "спина (ширина/толщина)", "side_delts" to "средняя дельта",
    "front_delts" to "передняя дельта", "rear_delts" to "задняя дельта", "biceps" to "бицепс",
    "triceps" to "трицепс", "quads" to "квадрицепс", "hams" to "бицепс бедра", "glutes" to "ягодицы",
    "calves" to "икры", "abs" to "пресс", "forearms" to "предплечья",
)

/** Крупнее — раньше: порядок для выбора главной мышцы при равном вкладе. */
private val SIZE = listOf("back", "chest", "quads", "hams", "glutes", "front_delts", "side_delts", "rear_delts",
    "triceps", "biceps", "calves", "forearms", "abs")

/**
 * Главная мышца упражнения — с наибольшим вкладом; при равенстве — крупная: тяга с отмеченными «спина + бицепс» —
 * упражнение на спину, жим «грудь + трицепс» — на грудь (порядок, в котором нажимали мышцы, не важен).
 */
fun mainMuscle(e: Exercise): String =
    e.muscles.entries.minWithOrNull(compareBy({ -it.value }, { SIZE.indexOf(it.key).let { i -> if (i < 0) SIZE.size else i } }))?.key.orEmpty()

fun defaultProgram(): Program =
    JSON.decodeFromString(Program.serializer(), Program::class.java.getResource("/default_program.json")!!.readText())

fun parseProgram(text: String): Program = JSON.decodeFromString(Program.serializer(), text).also { p ->
    p.days.flatMap { it.exercises }.forEach { id -> requireNotNull(p.ex(id)) { "упражнение '$id' не описано" } }
}
