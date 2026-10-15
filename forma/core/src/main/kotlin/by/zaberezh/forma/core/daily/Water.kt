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

/** Выпитый стакан воды (по нажатию на уведомление или кнопкой в приложении). */
@kotlinx.serialization.Serializable
data class Glass(val ml: Int = WaterLog.GLASS_ML)

val GLASS = by.zaberezh.forma.core.store.Kind("water.glass", Glass.serializer())

/** Счётчик воды за день и норма: ~30 мл на кг веса, округлённо до стаканов по 250 мл. */
object WaterLog {
    const val GLASS_ML = 250

    fun add(s: by.zaberezh.forma.core.store.Store, now: Long = System.currentTimeMillis()) = GLASS.save(s, Glass(), ts = now)

    /** Убрать последний стакан за день (если нажал по ошибке). */
    fun undo(s: by.zaberezh.forma.core.store.Store, day: java.time.LocalDate) {
        GLASS.all(s, day.atStartOfDay(by.zaberezh.forma.core.store.ZONE).toInstant().toEpochMilli(),
            day.plusDays(1).atStartOfDay(by.zaberezh.forma.core.store.ZONE).toInstant().toEpochMilli() - 1).lastOrNull()?.let { s.delete(it.first.id) }
    }

    fun ml(s: by.zaberezh.forma.core.store.Store, day: java.time.LocalDate): Int =
        GLASS.all(s, day.atStartOfDay(by.zaberezh.forma.core.store.ZONE).toInstant().toEpochMilli(),
            day.plusDays(1).atStartOfDay(by.zaberezh.forma.core.store.ZONE).toInstant().toEpochMilli() - 1).sumOf { it.second.ml }

    fun goalMl(s: by.zaberezh.forma.core.store.Store): Int {
        val kg = by.zaberezh.forma.core.body.latestWeight(s) ?: 70.0
        return (Math.round(kg * 30 / GLASS_ML) * GLASS_ML).toInt().coerceIn(1500, 4000)
    }

    fun percent(s: by.zaberezh.forma.core.store.Store, day: java.time.LocalDate) = ml(s, day) * 100 / goalMl(s)
}
