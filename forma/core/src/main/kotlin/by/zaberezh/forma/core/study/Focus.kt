package by.zaberezh.forma.core.study

import by.zaberezh.forma.core.store.Kind
import by.zaberezh.forma.core.store.Store
import kotlinx.serialization.Serializable
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

/** Фокус-сессия над лабой: сколько минут реально поработал. */
@Serializable
data class FocusSession(val lab: String, val start: Long, val minutes: Int)

val FOCUS = Kind("study.focus", FocusSession.serializer())

/** Идущая фокус-сессия (одна на всё приложение). */
@Serializable
data class FocusRun(val lab: String, val start: Long, val minutes: Int) {
    fun endsAt() = start + minutes * 60_000L
    fun left(now: Long) = ((endsAt() - now) / 60_000L).coerceAtLeast(0)
}

object Focus {
    const val DEFAULT_MIN = 25
    private const val ACTIVE = "study.focus.active"

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

    fun active(s: Store): FocusRun? = s.kvGet(ACTIVE)?.let { runCatching { by.zaberezh.forma.core.store.JSON.decodeFromString(FocusRun.serializer(), it) }.getOrNull() }

    fun start(s: Store, lab: String, now: Long = System.currentTimeMillis(), minutes: Int = DEFAULT_MIN): FocusRun {
        active(s)?.let { stop(s, now) }   // одна сессия за раз: прошлую закрываем
        val r = FocusRun(lab, now, minutes)
        s.kvPut(ACTIVE, by.zaberezh.forma.core.store.JSON.encodeToString(FocusRun.serializer(), r))
        return r
    }

    /** Закончить (по таймеру или вручную): засчитываются реально прошедшие минуты, но не больше плана. */
    fun stop(s: Store, now: Long = System.currentTimeMillis()): FocusSession? {
        val r = active(s) ?: return null
        s.kvPut(ACTIVE, null)
        val min = ((now - r.start) / 60_000L).toInt().coerceIn(0, r.minutes)
        if (min < 1) return null
        val f = FocusSession(r.lab, r.start, min)
        FOCUS.save(s, f, ts = r.start)
        return f
    }

    /** Всего минут фокуса по лабе. */
    fun minutes(s: Store, lab: String): Int = FOCUS.all(s).filter { it.second.lab == lab }.sumOf { it.second.minutes }

    fun minutesOn(s: Store, day: java.time.LocalDate): Int =
        FOCUS.all(s).filter { it.first.day == day }.sumOf { it.second.minutes }
}
