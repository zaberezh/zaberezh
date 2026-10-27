package by.zaberezh.forma.core.study

import by.zaberezh.forma.core.store.Pref
import by.zaberezh.forma.core.store.Store
import kotlinx.serialization.Serializable

/** Свои аудитории консультаций: ключ — консультация ([Rooms.key]), значение — аудитории. */
@Serializable data class RoomEdits(val rooms: Map<String, List<String>> = emptyMap())

val ROOMS = Pref("study.rooms", RoomEdits.serializer()) { RoomEdits() }

/**
 * Аудитория консультации, исправленная руками: в ИИС её часто нет или она меняется. Хранится отдельно
 * от расписания — обновление расписания с сервера её не стирает. Действует на все такие консультации
 * (тот же предмет, день недели и время; у разовой — та же дата).
 */
object Rooms {
    fun editable(l: Lesson) = l.type.trim().startsWith("конс", ignoreCase = true)

    fun key(l: Lesson) = listOf(l.subject, l.type, l.date ?: "d${l.weekday}", l.start, l.subgroup).joinToString("|")

    fun edited(e: RoomEdits, l: Lesson) = key(l) in e.rooms

    /** Пары со своими аудиториями вместо расписания. */
    fun apply(e: RoomEdits, lessons: List<Lesson>): List<Lesson> =
        if (e.rooms.isEmpty()) lessons else lessons.map { l -> e.rooms[key(l)]?.let { l.copy(rooms = it) } ?: l }

    /** «315-4 к., 501» → аудитории; пусто или null — вернуть аудиторию из расписания. */
    fun set(s: Store, l: Lesson, text: String?) {
        val rooms = text.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val cur = ROOMS.get(s).rooms
        ROOMS.set(s, RoomEdits(if (rooms.isEmpty()) cur - key(l) else cur + (key(l) to rooms)))
    }
}
