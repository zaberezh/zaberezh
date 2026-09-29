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

/** Итог дня; [sets] — подходы по отдельности (если вводились подходами), сумма = [n]. */
@Serializable data class DayCount(val counter: String, val date: String, val n: Int, val sets: List<Int> = emptyList())

val COUNT = Kind("daily.count", DayCount.serializer())

object CounterModule : Module {
    override val id = "daily"
    override val title = "Ежедневное"

    private fun key(c: String, d: LocalDate) = "count:$c:$d"

    fun day(s: Store, c: String, d: LocalDate): DayCount = s.get(key(c, d))?.let(COUNT::decode) ?: DayCount(c, d.toString(), 0)

    /** Значение за день; не отмечено — 0. */
    fun get(s: Store, c: String, d: LocalDate): Int = day(s, c, d).n

    private fun save(s: Store, v: DayCount, d: LocalDate) =
        COUNT.save(s, v, ts = d.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli(), id = key(v.counter, d))

    /** Задать итог дня числом (подходы сбрасываются). */
    fun set(s: Store, c: String, d: LocalDate, n: Int) = save(s, DayCount(c, d.toString(), n.coerceIn(0, 10_000)), d)

    /** Добавить подход. Если итог был введён числом без подходов — он сохраняется первым «подходом». */
    fun addSet(s: Store, c: String, d: LocalDate, reps: Int) {
        if (reps <= 0) return
        val cur = day(s, c, d)
        val sets = (if (cur.sets.isEmpty() && cur.n > 0) listOf(cur.n) else cur.sets) + reps
        save(s, cur.copy(n = sets.sum(), sets = sets), d)
    }

    fun removeSet(s: Store, c: String, d: LocalDate, index: Int) {
        val cur = day(s, c, d)
        if (index !in cur.sets.indices) return
        val sets = cur.sets.filterIndexed { i, _ -> i != index }
        save(s, cur.copy(n = sets.sum(), sets = sets), d)
    }

    /** Лучший подход за всё время (из дней, введённых подходами). */
    fun bestSet(s: Store, c: String): Pair<LocalDate, Int>? =
        COUNT.all(s).map { it.second }.filter { it.counter == c && it.sets.isNotEmpty() }
            .map { LocalDate.parse(it.date) to it.sets.max() }.maxByOrNull { it.second }

    /** Все дни периода, пропуски = 0. */
    fun series(s: Store, c: String, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Int>> =
        (0..ChronoUnit.DAYS.between(from, to)).map { from.plusDays(it) }.map { it to get(s, c, it) }

    private fun used(s: Store, c: String) = COUNT.all(s).any { it.second.counter == c }

    override fun morning(ctx: Ctx): List<String> = ctx.settings.counters.filter { used(ctx.store, it.id) }.map { d ->
        val y = ctx.today.minusDays(1)
        val week = series(ctx.store, d.id, ctx.today.minusDays(7), y).sumOf { it.second }
        val yd = day(ctx.store, d.id, y)
        "${d.title} вчера: ${yd.n}" + (if (yd.sets.size > 1) " (${yd.sets.joinToString("+")})" else "") + " · за 7 дней: $week"
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
