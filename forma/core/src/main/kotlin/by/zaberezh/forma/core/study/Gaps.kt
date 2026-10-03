package by.zaberezh.forma.core.study

import java.time.LocalTime

/** Окно между парами: с конца одной до начала следующей. */
data class Gap(val from: LocalTime, val to: LocalTime) {
    val minutes get() = java.time.Duration.between(from, to).toMinutes().toInt()

    /** «окно 11:30–13:25 · 1 ч 55 мин» — одинаково на «Сегодня» и в расписании. */
    fun label(): String {
        val len = if (minutes < 60) "$minutes мин" else "${minutes / 60} ч" + (if (minutes % 60 > 0) " ${minutes % 60} мин" else "")
        return "окно $from–$to · $len"
    }
}

object Gaps {
    /** Окна между парами не короче [minMinutes] (перемены по 10–15 минут — не окна). */
    fun gaps(lessons: List<Lesson>, minMinutes: Int = 30): List<Gap> {
        val spans = lessons.mapNotNull { l ->
            val a = runCatching { LocalTime.parse(l.start) }.getOrNull()
            val b = runCatching { LocalTime.parse(l.end) }.getOrNull()
            if (a != null && b != null && b > a) a to b else null
        }.sortedBy { it.first }
        val out = mutableListOf<Gap>()
        var end: LocalTime? = null
        for ((a, b) in spans) {
            val e = end
            if (e != null && a > e && java.time.Duration.between(e, a).toMinutes() >= minMinutes) out += Gap(e, a)
            end = if (e == null || b > e) b else e
        }
        return out
    }
}
