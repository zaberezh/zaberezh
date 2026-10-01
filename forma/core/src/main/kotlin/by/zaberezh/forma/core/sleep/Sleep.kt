package by.zaberezh.forma.core.sleep

import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.Module
import by.zaberezh.forma.core.Section
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.store.Kind
import by.zaberezh.forma.core.store.Store
import by.zaberezh.forma.core.store.ZONE
import by.zaberezh.forma.core.store.endMs
import by.zaberezh.forma.core.store.startMs
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

/** Ночь: засыпание (последнее выключение экрана), подъём (первая разблокировка), ночные проверки телефона. */
@Serializable
data class Night(val start: Long, val end: Long, val wakeups: Int = 0, val awakeMin: Int = 0, val source: String = "screen") {
    val minutes: Int get() = ((end - start) / 60_000).toInt() - awakeMin
}

val NIGHT = Kind("sleep.night", Night.serializer())

private const val MIN = 60_000L
private val HM = DateTimeFormatter.ofPattern("HH:mm")

fun hm(ms: Long): String = HM.format(Instant.ofEpochMilli(ms).atZone(ZONE))
fun dur(min: Int): String = "${min / 60} ч ${"%02d".format(min % 60)} мин"

/** Окно поиска ночи, заканчивающейся утром [day]: с 18:00 накануне до 16:00. */
fun nightWindow(day: LocalDate): Pair<Long, Long> =
    day.minusDays(1).atTime(18, 0).atZone(ZONE).toInstant().toEpochMilli() to day.atTime(16, 0).atZone(ZONE).toInstant().toEpochMilli()

/**
 * Определение сна по использованию телефона.
 * [events] — (время, true = начал пользоваться / false = экран выключен), по возрастанию.
 * Берём самый длинный промежуток без использования ≥ 3 ч; короткие ночные сессии (≤ 5 мин)
 * между долгими паузами не разрывают сон, а считаются пробуждениями.
 * [now] — если сон ещё идёт (пауза тянется до «сейчас» внутри окна), возвращаем null.
 */
fun detectNight(events: List<Pair<Long, Boolean>>, from: Long, to: Long, now: Long = Long.MAX_VALUE): Night? {
    val end = minOf(to, now)
    val ev = events.filter { it.first in from..end }.sortedBy { it.first }
    // паузы (экран не используется)
    val gaps = mutableListOf<LongArray>()
    var offSince: Long? = if (ev.isEmpty() || ev.first().second) from else null
    for ((t, on) in ev) {
        if (!on && offSince == null) offSince = t
        if (on && offSince != null) { gaps += longArrayOf(offSince, t); offSince = null }
    }
    offSince?.let { gaps += longArrayOf(it, end) }
    if (gaps.isEmpty()) return null

    // склейка пауз через короткие ночные сессии
    data class G(var s: Long, var e: Long, var wake: Int = 0, var awake: Long = 0)
    val merged = mutableListOf<G>()
    for (g in gaps) {
        val last = merged.lastOrNull()
        val between = last?.let { g[0] - it.e }
        if (last != null && between!! <= 5 * MIN && last.e - last.s >= 20 * MIN && g[1] - g[0] >= 20 * MIN) {
            last.e = g[1]; last.wake++; last.awake += between
        } else merged += G(g[0], g[1])
    }
    // ночной сон обязан захватывать 03:00–06:00 утра — иначе это дневная пауза (пары, работа)
    val night3 = to - 13 * 60 * MIN; val night6 = to - 10 * 60 * MIN // окно заканчивается в 16:00
    val best = merged.filter { it.s <= night6 && it.e >= night3 }.maxByOrNull { it.e - it.s - it.awake } ?: return null
    if (best.e - best.s - best.awake < 3 * 60 * MIN) return null
    if (best.e >= end && end < to) return null // ещё спит — не финализируем
    return Night(best.s, best.e, best.wake, (best.awake / MIN).toInt())
}

fun nightId(day: LocalDate) = "sleep:$day"

/** Минуты от полудня — чтобы усреднять время отбоя через полночь. */
private fun sinceNoon(ms: Long): Int {
    val t = Instant.ofEpochMilli(ms).atZone(ZONE).toLocalTime()
    return ((t.toSecondOfDay() / 60) - 12 * 60).mod(24 * 60)
}
private fun fromNoon(m: Int): String = LocalTime.NOON.plusMinutes(m.toLong()).format(HM)

object SleepModule : Module {
    override val id = "sleep"
    override val title = "Сон"

    fun nights(s: Store, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Night>> =
        NIGHT.all(s, from.minusDays(1).startMs(), to.endMs()).map { (e, n) -> Instant.ofEpochMilli(n.end).atZone(ZONE).toLocalDate() to n }
            .filter { it.first in from..to }

    fun lastNight(s: Store, today: LocalDate) = nights(s, today, today).lastOrNull()?.second

    /**
     * Подъём в день [day]: если включён будильник по парам и в этот день есть пары — по первой паре
     * (8:30 → 7:10, 10:05 → 8:30), иначе — время подъёма из настроек сна.
     */
    fun wake(ctx: Ctx, day: LocalDate): LocalTime {
        val st = ctx.settings
        if (st.wakeAlarm) {
            val tt = by.zaberezh.forma.core.study.TIMETABLE.get(ctx.store)
            by.zaberezh.forma.core.study.Study.wakeAt(tt, day, by.zaberezh.forma.core.study.STUDY_PREFS.get(ctx.store).subgroup)
                ?.let { return it.toLocalTime() }
        }
        return LocalTime.of(st.wakeHour, st.wakeMinute)
    }

    /** Отбой сегодня: завтрашний подъём минус цель сна — завтра к первой паре ложишься раньше, к третьей — позже. */
    fun bedtime(ctx: Ctx): LocalTime = wake(ctx, ctx.today.plusDays(1)).minusMinutes((ctx.settings.sleepTargetH * 60).toLong())

    data class Stats(val n: Int, val avgMin: Int, val bedAvg: String, val wakeAvg: String, val bedSdMin: Int, val short: Int)

    fun stats(ctx: Ctx, from: LocalDate, to: LocalDate): Stats? {
        val l = nights(ctx.store, from, to).map { it.second }
        if (l.isEmpty()) return null
        val beds = l.map { sinceNoon(it.start) }
        val mean = beds.average()
        val sd = sqrt(beds.sumOf { (it - mean) * (it - mean) } / beds.size)
        val target = (ctx.settings.sleepTargetH * 60).toInt()
        return Stats(l.size, l.map { it.minutes }.average().toInt(), fromNoon(mean.toInt()),
            fromNoon(l.map { sinceNoon(it.end) }.average().toInt()), sd.toInt(), l.count { it.minutes < target - 60 })
    }

    override fun morning(ctx: Ctx): List<String> {
        val n = lastNight(ctx.store, ctx.today) ?: return emptyList()
        return listOf("Сон: ${hm(n.start)}–${hm(n.end)}, ${dur(n.minutes)}" + if (n.wakeups > 0) " (проверок телефона: ${n.wakeups})" else "")
    }

    override fun checkup(ctx: Ctx, from: LocalDate, to: LocalDate): Section? {
        val st = stats(ctx, from, to) ?: return null
        val target = ctx.settings.sleepTargetH
        val lines = listOf(
            "Ночей с данными: ${st.n}",
            "Средний сон: ${dur(st.avgMin)} (цель ${target.r1()} ч)",
            "Отбой в среднем: ${st.bedAvg} (разброс ±${st.bedSdMin} мин)",
            "Подъём в среднем: ${st.wakeAvg}",
            "Ночей короче цели на час+: ${st.short}",
        )
        val actions = mutableListOf<String>()
        if (st.avgMin < target * 60 - 30)
            actions += "Недосып ${dur((target * 60).toInt() - st.avgMin)} в среднем — главный ограничитель роста: отбой в ${bedtime(ctx)}."
        if (st.bedSdMin > 60) actions += "Отбой плавает на ±${st.bedSdMin} мин — фиксируй время подъёма и отбоя, в т.ч. в выходные."
        return Section(title, lines, actions, mapOf("nights" to st.n, "avg_sleep_min" to st.avgMin, "bed_avg" to st.bedAvg,
            "bed_sd_min" to st.bedSdMin, "wake_avg" to st.wakeAvg, "target_h" to target, "short_nights" to st.short))
    }
}
