package by.zaberezh.forma.core.study

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Экзамен/консультация сессии на дату. */
data class SessionItem(val day: LocalDate, val lesson: Lesson) {
    val exam get() = lesson.type.startsWith("Экз", true) || lesson.type.startsWith("Зач", true) || lesson.type.contains("экзамен", true)
}

/**
 * Сессия: даты из ИИС (startExamsDate–endExamsDate) и разовые занятия с датой (экзамены, консультации).
 * [daysLeft] — до начала сессии (или до первого экзамена, если дат сессии нет); [running] — сессия идёт.
 */
data class SessionInfo(val start: LocalDate?, val end: LocalDate?, val items: List<SessionItem>, val today: LocalDate) {
    val upcoming get() = items.filter { !it.day.isBefore(today) }
    val exams get() = upcoming.filter { it.exam }
    val begins get() = start ?: items.firstOrNull { it.exam }?.day ?: items.firstOrNull()?.day
    val daysLeft get() = begins?.let { ChronoUnit.DAYS.between(today, it).toInt() }
    val running get() = begins != null && !today.isBefore(begins) && (end?.let { !today.isAfter(it) } ?: upcoming.isNotEmpty())
    val known get() = begins != null
}

object Session {
    fun of(tt: Timetable, today: LocalDate): SessionInfo {
        val items = tt.lessons.filter { it.date != null }
            .mapNotNull { l -> runCatching { SessionItem(LocalDate.parse(l.date), l) }.getOrNull() }
            .sortedWith(compareBy({ it.day }, { it.lesson.start }))
        return SessionInfo(tt.examsStart?.let(LocalDate::parse), tt.examsEnd?.let(LocalDate::parse), items, today)
    }
}
