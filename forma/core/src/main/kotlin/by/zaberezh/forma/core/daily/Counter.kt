package by.zaberezh.forma.core.daily

import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.Module
import by.zaberezh.forma.core.Section
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.store.Kind
import by.zaberezh.forma.core.store.Store
import by.zaberezh.forma.core.store.ZONE
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Ежедневный счётчик (подтягивания, отжимания…). Новый счётчик = новая строка в настройках, без кода. */
@Serializable data class CounterDef(val id: String, val title: String, val unit: String = "раз")

@Serializable data class DayCount(val counter: String, val date: String, val n: Int)

val COUNT = Kind("daily.count", DayCount.serializer())

object CounterModule : Module {
    override val id = "daily"
    override val title = "Ежедневное"

    private fun key(c: String, d: LocalDate) = "count:$c:$d"

    /** Значение за день; не отмечено — 0. */
    fun get(s: Store, c: String, d: LocalDate): Int = s.get(key(c, d))?.let(COUNT::decode)?.n ?: 0

    fun set(s: Store, c: String, d: LocalDate, n: Int) {
        COUNT.save(s, DayCount(c, d.toString(), n.coerceIn(0, 10_000)), ts = d.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli(), id = key(c, d))
    }

    /** Все дни периода, пропуски = 0. */
    fun series(s: Store, c: String, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Int>> =
        (0..ChronoUnit.DAYS.between(from, to)).map { from.plusDays(it) }.map { it to get(s, c, it) }

    private fun used(s: Store, c: String) = COUNT.all(s).any { it.second.counter == c }

    override fun morning(ctx: Ctx): List<String> = ctx.settings.counters.filter { used(ctx.store, it.id) }.map { d ->
        val y = ctx.today.minusDays(1)
        val week = series(ctx.store, d.id, ctx.today.minusDays(7), y).sumOf { it.second }
        "${d.title} вчера: ${get(ctx.store, d.id, y)} · за 7 дней: $week"
    }

    override fun checkup(ctx: Ctx, from: LocalDate, to: LocalDate): Section? {
        val defs = ctx.settings.counters.filter { used(ctx.store, it.id) }
        if (defs.isEmpty()) return null
        val lines = mutableListOf<String>(); val actions = mutableListOf<String>(); val facts = mutableMapOf<String, Any?>()
        defs.forEach { d ->
            val ser = series(ctx.store, d.id, from, to)
            val total = ser.sumOf { it.second }
            val zero = ser.count { it.second == 0 }
            val best = ser.maxByOrNull { it.second }
            val half = ser.size / 2
            val a = ser.take(half).map { it.second }.average(); val b = ser.drop(half).map { it.second }.average()
            lines += "${d.title}: всего $total ${d.unit}, в среднем ${(total.toDouble() / ser.size).r1()}/день, " +
                "пропусков $zero из ${ser.size}, лучший день ${best?.second ?: 0}"
            lines += "${d.title}: первая половина ${a.r1()}/день → вторая ${b.r1()}/день"
            if (zero * 2 > ser.size) actions += "${d.title}: пропущено $zero из ${ser.size} дней — регулярность важнее объёма."
            facts[d.id] = mapOf("total" to total, "zero_days" to zero, "days" to ser.size, "avg_first_half" to a, "avg_second_half" to b)
        }
        return Section(title, lines, actions, facts)
    }
}
