package by.zaberezh.forma.core.food

import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.Module
import by.zaberezh.forma.core.Profile
import by.zaberezh.forma.core.Section
import by.zaberezh.forma.core.body.dailyWeights
import by.zaberezh.forma.core.body.latestWeight
import by.zaberezh.forma.core.body.weightRate
import by.zaberezh.forma.core.i
import by.zaberezh.forma.core.r2
import by.zaberezh.forma.core.slope
import by.zaberezh.forma.core.store.Kind
import by.zaberezh.forma.core.store.Store
import by.zaberezh.forma.core.store.endMs
import by.zaberezh.forma.core.store.startMs
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Serializable
data class Macro(val kcal: Double = 0.0, val p: Double = 0.0, val f: Double = 0.0, val c: Double = 0.0, val fib: Double = 0.0) {
    operator fun plus(o: Macro) = Macro(kcal + o.kcal, p + o.p, f + o.f, c + o.c, fib + o.fib)
    operator fun times(k: Double) = Macro(kcal * k, p * k, f * k, c * k, fib * k)
    fun short() = "${kcal.i()} ккал · Б ${p.i()} · Ж ${f.i()} · У ${c.i()}"
}

@Serializable
data class FoodItem(
    val name: String,
    val grams: Double,
    val per100: Macro,
    val source: String = "",   // откуда цифры: сайт заведения, этикетка, оценка…
    val conf: String = "",     // high / medium / low
) {
    val total: Macro get() = per100 * (grams / 100.0)
}

@Serializable data class Meal(val text: String, val items: List<FoodItem>)

@Serializable
data class LibFood(val name: String, val grams: Double, val per100: Macro, val source: String = "", val uses: Int = 1, val aliases: List<String> = emptyList())

val MEAL = Kind("food.meal", Meal.serializer())
val LIB = Kind("food.lib", LibFood.serializer())

data class Targets(val kcal: Int, val p: Int, val f: Int, val c: Int, val tdee: Int, val tdeeNote: String, val fib: Int = FIBER_GOAL)

/** Клетчатка в день, г — рекомендация ВОЗ/EFSA для взрослых (25–30+). */
const val FIBER_GOAL = 30

fun norm(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

/** BMR Миффлина–Сан Жеора × активность. */
fun formulaTdee(p: Profile, kg: Double): Double =
    (10 * kg + 6.25 * p.heightCm - 5 * p.age + if (p.male) 5 else -161) * p.activity

/**
 * Адаптивный TDEE: средние калории − (наклон веса × 7700 ккал/кг).
 * Возвращает (оценка, доверие 0..1) или null, если данных мало.
 */
fun adaptiveTdee(weights: List<Pair<LocalDate, Double>>, intake: Map<LocalDate, Double>, to: LocalDate): Pair<Double, Double>? {
    val logged = intake.filterValues { it >= 1000 }
    if (logged.size < 10 || weights.size < 6) return null
    if (ChronoUnit.DAYS.between(weights.first().first, weights.last().first) < 10) return null
    val k = slope(weights.map { ChronoUnit.DAYS.between(to, it.first).toDouble() to it.second }) ?: return null
    return (logged.values.average() - k * 7700) to (logged.size / 21.0).coerceAtMost(1.0)
}

object FoodModule : Module {
    override val id = "food"
    override val title = "Питание"

    fun meals(s: Store, d: LocalDate) = MEAL.all(s, d.startMs(), d.endMs())
    fun dayTotal(s: Store, d: LocalDate): Macro = meals(s, d).flatMap { it.second.items }.fold(Macro()) { a, i -> a + i.total }

    fun intake(s: Store, from: LocalDate, to: LocalDate): Map<LocalDate, Double> =
        MEAL.all(s, from.startMs(), to.endMs()).groupBy { it.first.day }
            .mapValues { (_, l) -> l.flatMap { it.second.items }.sumOf { it.total.kcal } }

    fun targets(ctx: Ctx): Targets {
        val st = ctx.settings; val p = st.profile
        val kg = latestWeight(ctx.store) ?: 70.5
        val formula = formulaTdee(p, kg)
        val from = ctx.today.minusDays(28); val to = ctx.today.minusDays(1)
        val ad = adaptiveTdee(dailyWeights(ctx.store, from, to), intake(ctx.store, from, to), to)
        val tdee = ad?.let { (v, w) -> v * w + formula * (1 - w) } ?: formula
        val note = ad?.let { "по данным ${it.first.i()} (доверие ${(it.second * 100).i()}%), формула ${formula.i()}" } ?: "формула (мало данных для адаптации)"
        val kcal = st.kcalOverride?.toDouble() ?: (tdee + p.gainKgPerWeek * 7700 / 7)
        val prot = p.proteinPerKg * kg
        val fat = kcal * p.fatShare / 9
        val carb = ((kcal - prot * 4 - fat * 9) / 4).coerceAtLeast(0.0)
        return Targets(kcal.i(), prot.i(), fat.i(), carb.i(), tdee.i(), note)
    }

    // ---- библиотека своих продуктов/блюд (кэш: второй раз без ИИ) ----
    fun libId(name: String) = "lib:" + norm(name)

    fun remember(s: Store, items: List<FoodItem>) = items.forEach { it ->
        val id = libId(it.name)
        val old = s.get(id)?.let(LIB::decode)
        LIB.save(s, LibFood(it.name, it.grams, it.per100, it.source, (old?.uses ?: 0) + 1, old?.aliases ?: emptyList()), id = id)
    }

    fun library(s: Store): List<LibFood> = LIB.all(s).map { it.second }.sortedByDescending { it.uses }

    override fun morning(ctx: Ctx): List<String> {
        val y = ctx.today.minusDays(1)
        if (meals(ctx.store, y).isEmpty()) return emptyList()
        val t = dayTotal(ctx.store, y); val g = targets(ctx)
        return listOf("Вчера: ${t.kcal.i()}/${g.kcal} ккал, белок ${t.p.i()}/${g.p} г")
    }

    override fun checkup(ctx: Ctx, from: LocalDate, to: LocalDate): Section {
        val s = ctx.store
        val days = ChronoUnit.DAYS.between(from, to) + 1
        val inMap = intake(s, from, to).filterValues { it >= 1000 }
        val logged = inMap.keys
        val g = targets(ctx)
        val lines = mutableListOf<String>()
        val actions = mutableListOf<String>()
        lines += "Еда записана: ${logged.size} из $days дней"
        val avg = logged.map { dayTotal(s, it) }.fold(Macro()) { a, m -> a + m } * (1.0 / logged.size.coerceAtLeast(1))
        if (logged.isNotEmpty()) lines += "Среднее: ${avg.short()} (цель ${g.kcal} ккал, Б ${g.p})"
        lines += "TDEE: ${g.tdee} ккал — ${g.tdeeNote}"
        if (logged.size < days * 0.7) actions += "Еда записана лишь ${logged.size}/$days дней — выводы по калориям неточные."
        if (logged.isNotEmpty() && avg.p < g.p * 0.9) actions += "Белок ${avg.p.i()} г при цели ${g.p} — добери (творог, яйца, курица, протеин)."

        val rate = weightRate(s, to, days)
        val target = ctx.settings.profile.gainKgPerWeek
        if (rate != null) {
            if (rate > target + 0.15) actions += "Вес растёт быстрее цели (${rate.r2()} кг/нед) → −150 ккал."
            else if (rate < target - 0.15) actions += "Вес ниже цели (${rate.r2()} кг/нед) → +150 ккал (если силовые стоят — обязательно)."
        }
        return Section(title, lines, actions, mapOf("days_logged" to logged.size, "days" to days,
            "avg_kcal" to avg.kcal.i(), "avg_protein" to avg.p.i(), "target_kcal" to g.kcal, "target_protein" to g.p, "tdee" to g.tdee))
    }
}
