package by.zaberezh.forma.core.daily

import by.zaberezh.forma.core.Settings
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Час дня, с которого напоминать о воде: будни — с [Settings.waterWeekdayFrom], выходные — с [Settings.waterWeekendFrom]. */
fun waterFrom(st: Settings, day: DayOfWeek) = if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) st.waterWeekendFrom else st.waterWeekdayFrom

/** Следующее напоминание «попить воды»: каждый час ровно в :00, с «от» до [Settings.waterTo] включительно. */
fun nextWaterTime(st: Settings, now: LocalDateTime): LocalDateTime {
    var t = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
    repeat(24 * 8) {
        if (t.hour in waterFrom(st, t.dayOfWeek)..st.waterTo) return t
        t = t.plusHours(1)
    }
    return t
}
