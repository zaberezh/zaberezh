package by.zaberezh.forma.core.gym

import by.zaberezh.forma.core.store.Kind
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Упражнение в плане дня: рабочие подходы и метка суперсета (A, B… — упражнения с одной меткой чередуются). */
@Serializable data class PlanItem(val ex: String, val sets: Int, val pair: String? = null)

/**
 * План тренировки на конкретную дату. Сохраняется, только когда его правили руками или начали тренировку —
 * иначе пересчитывается на лету из базы упражнений и истории.
 */
@Serializable
data class DayPlan(
    val date: String,
    val items: List<PlanItem>,
    val focus: String? = null,      // ключ из FOCUS или null (авто)
    val source: String = "auto",    // auto / edited / claude
    val note: String = "",
)

val DAYPLAN = Kind("gym.dayplan", DayPlan.serializer())

/** Акценты дня по желанию. */
val FOCUS: Map<String, Pair<String, Set<String>>> = linkedMapOf(
    "arms" to ("Руки" to setOf("biceps", "triceps", "forearms")),
    "chest" to ("Грудь" to setOf("chest")),
    "back" to ("Спина" to setOf("back", "rear_delts")),
    "shoulders" to ("Плечи" to setOf("side_delts", "front_delts", "rear_delts")),
    "legs" to ("Ноги" to setOf("quads", "hams", "glutes", "calves")),
    "abs" to ("Пресс" to setOf("abs")),
    "forearms" to ("Предплечья" to setOf("forearms")),
)

/** Недельный объём по умолчанию (тяжёлые подходы): мин–макс. */
val DEFAULT_VOLUME: Map<String, List<Int>> = mapOf(
    "chest" to listOf(8, 14), "back" to listOf(10, 16), "side_delts" to listOf(8, 16), "rear_delts" to listOf(4, 10),
    "biceps" to listOf(6, 12), "triceps" to listOf(6, 12), "quads" to listOf(6, 12), "hams" to listOf(4, 10),
    "glutes" to listOf(0, 12), "front_delts" to listOf(0, 12), "calves" to listOf(0, 8), "abs" to listOf(0, 8),
    "forearms" to listOf(6, 10),   // акцент пользователя: предплечья
)

/** Мелкие мышцы: упражнение с такой главной мышцей — изоляция, даже если задевает ещё одну (молотки, обратный хват). */
private val SMALL = setOf("biceps", "triceps", "forearms", "calves", "abs", "side_delts", "rear_delts")

/** Базовое (многосуставное) упражнение: несколько мышц и главная — крупная. */
fun isCompound(e: Exercise): Boolean =
    e.muscles.size > 1 && (e.muscles.maxByOrNull { it.value }?.key ?: "") !in SMALL

/** Визуальный приоритет (V-силуэт): средняя дельта, спина, грудь, руки. */
private val PRIORITY = mapOf("side_delts" to 1.3, "back" to 1.2, "chest" to 1.15, "forearms" to 1.15, "biceps" to 1.1, "triceps" to 1.1)

/**
 * Составитель тренировки дня по методике:
 * - недельный объём на мышцу делится на число тренировок; недобор за 7 дней повышает приоритет, перебор — снижает;
 * - мышца, нагруженная вчера, почти не берётся (×0.3), позавчера — ×0.8 (восстановление 48–72 ч);
 * - сначала базовые (многосуставные), потом изоляция; не больше 2 упражнений на одну главную мышцу;
 * - бюджет ~18 рабочих подходов (60–75 мин) — умеренно, с учётом недосыпа;
 * - упражнение, которое делал в последние 3 дня, берётся реже (разнообразие).
 */
object Planner {
    const val SESSION_SETS = 18
    const val MAX_EXERCISES = 7

    /** Подходы на мышцу по дням (≥5 повторов, косвенные с коэффициентом). */
    fun muscleLog(p: Program, sessions: List<Pair<LocalDate, List<SetLog>>>): List<Pair<LocalDate, Map<String, Double>>> =
        sessions.map { (d, sets) ->
            val m = HashMap<String, Double>()
            sets.filter { it.r >= 5 }.forEach { st -> p.ex(st.ex)?.muscles?.forEach { (k, v) -> m[k] = (m[k] ?: 0.0) + v } }
            d to m
        }

    /** Перевод плана в «подходы на мышцу» — для симуляции будущих дней недели. */
    fun planMuscles(p: Program, plan: DayPlan): Map<String, Double> {
        val m = HashMap<String, Double>()
        plan.items.forEach { it -> p.ex(it.ex)?.muscles?.forEach { (k, v) -> m[k] = (m[k] ?: 0.0) + v * it.sets } }
        return m
    }

    fun build(
        p: Program, date: LocalDate, log: List<Pair<LocalDate, Map<String, Double>>>,
        lastUsed: Map<String, LocalDate>, sessionsPerWeek: Int, focus: String? = null,
    ): DayPlan {
        if (p.exercises.isEmpty()) return DayPlan(date.toString(), emptyList(), focus)
        val volume = DEFAULT_VOLUME + p.volume
        val focusSet = focus?.let { FOCUS[it]?.second } ?: emptySet()
        val recent = log.filter { it.first < date && ChronoUnit.DAYS.between(it.first, date) <= 7 }
        val need = HashMap<String, Double>()
        volume.forEach { (m, range) ->
            val target = (range[0] + range[1]) / 2.0
            if (target <= 0) return@forEach
            val done = recent.sumOf { it.second[m] ?: 0.0 }
            val deficit = ((target - done) / target).coerceIn(0.0, 1.0)
            var n = target / sessionsPerWeek.coerceAtLeast(1) * (0.5 + deficit)
            val last = recent.filter { (it.second[m] ?: 0.0) >= 2 }.maxOfOrNull { it.first }
            val gap = last?.let { ChronoUnit.DAYS.between(it, date) } ?: 99
            n *= when { gap <= 1 -> 0.3; gap == 2L -> 0.8; else -> 1.0 }
            n *= PRIORITY[m] ?: 1.0
            if (focusSet.isNotEmpty()) n *= if (m in focusSet) 2.5 else 0.45
            need[m] = n
        }
        // мышцы с нулевым недельным минимумом (пресс, икры…) берутся при фокусе или если есть «запас» бюджета
        focusSet.forEach { m -> if ((need[m] ?: 0.0) < 2.0) need[m] = 3.0 }

        val chosen = mutableListOf<Pair<Exercise, Int>>()
        var total = 0
        val perMain = HashMap<String, Int>()
        fun main(e: Exercise) = e.muscles.maxByOrNull { it.value }?.key ?: ""
        while (total < SESSION_SETS && chosen.size < MAX_EXERCISES) {
            val best = p.exercises.filter { e -> chosen.none { it.first.id == e.id } && (perMain[main(e)] ?: 0) < (if (main(e) in focusSet) 3 else 2) }
                .map { e ->
                    var score = e.muscles.entries.sumOf { (m, k) -> k * (need[m] ?: 0.0) }
                    if (isCompound(e)) score *= 1.15                                      // базовые — вперёд
                    if ((perMain[main(e)] ?: 0) >= 1) score *= 0.5                        // второе на ту же мышцу — реже
                    lastUsed[e.id]?.let { if (ChronoUnit.DAYS.between(it, date) in 1..3) score *= 0.7 }
                    e to score
                }.maxByOrNull { it.second } ?: break
            if (best.second < 0.8) break
            val e = best.first
            val sets = e.sets.coerceAtMost(SESSION_SETS + 2 - total).coerceAtLeast(1)
            chosen += e to sets
            total += sets
            perMain[main(e)] = (perMain[main(e)] ?: 0) + 1
            e.muscles.forEach { (m, k) -> need[m] = ((need[m] ?: 0.0) - k * sets).coerceAtLeast(0.0) }
        }
        return DayPlan(date.toString(), chosen.map { PlanItem(it.first.id, it.second) }, focus)
    }

    // ---------- порядок и суперсеты ----------

    private enum class Kind { PUSH, PULL, LEGS, ARMS_FLEX, ARMS_EXT, OTHER }

    private fun main(e: Exercise) = e.muscles.maxByOrNull { it.value }?.key ?: ""

    private fun kind(e: Exercise): Kind = when (main(e)) {
        "chest", "front_delts" -> Kind.PUSH
        "triceps" -> if (e.muscles.size > 1) Kind.PUSH else Kind.ARMS_EXT
        "back", "rear_delts" -> Kind.PULL
        "biceps" -> if (e.muscles.size > 1) Kind.PULL else Kind.ARMS_FLEX
        "quads", "hams", "glutes", "calves" -> Kind.LEGS
        else -> Kind.OTHER // средняя дельта, пресс, предплечья
    }

    /** Порядок изоляции: визуальный приоритет → руки → мелкие (пресс, икры, предплечья) в конце. */
    private val ISO_RANK = mapOf("side_delts" to 0, "rear_delts" to 1, "chest" to 2, "back" to 2, "quads" to 3, "hams" to 3, "glutes" to 3,
        "biceps" to 4, "triceps" to 4, "front_delts" to 5, "calves" to 6, "forearms" to 7, "abs" to 8)

    /**
     * Порядок упражнений и пары суперсетов.
     * - Порядок на гипертрофию почти не влияет (Nunes 2021, 11 исследований), но первым упражнениям достаются
     *   больший прирост силы и больше повторов (Simão 2012) → сначала базовые на приоритетные мышцы (спина, грудь),
     *   ноги, затем изоляция по приоритету (средняя дельта первой), руки ближе к концу — чтобы не «убить»
     *   бицепс/трицепс до тяг и жимов, где они вспомогательные. Предутомление пользы не даёт (Trindade 2019).
     * - Суперсеты агонист–антагонист (жим ↔ тяга, бицепс ↔ трицепс) дают тот же рост и силу при ~30–50% меньшем
     *   времени и сохраняют объём (Robbins 2010; мета-анализ Sports Med 2025; RCT «Less time, same gains» 2025).
     *   Суперсеты на одну мышцу режут объём — не делаем. Тяжёлые базовые на ноги — отдельно (системная усталость),
     *   допускается пара с мелкой изоляцией, которая им не мешает (махи, пресс).
     */
    fun arrange(p: Program, items: List<PlanItem>, supersets: Boolean): List<PlanItem> {
        val known = items.filter { p.ex(it.ex) != null }
        val (comp, iso) = known.partition { isCompound(p.ex(it.ex)!!) }
        val compOrder = comp.sortedWith(compareBy<PlanItem> { if (kind(p.ex(it.ex)!!) == Kind.LEGS) 1 else 0 }
            .thenByDescending { PRIORITY[main(p.ex(it.ex)!!)] ?: 1.0 })
        val isoOrder = iso.sortedBy { ISO_RANK[main(p.ex(it.ex)!!)] ?: 5 }
        val ordered = (compOrder + isoOrder).map { it.copy(pair = null) }
        if (!supersets) return ordered

        fun fits(a: Exercise, b: Exercise): Boolean {
            val ka = kind(a); val kb = kind(b)
            val ca = a.muscles.size > 1; val cb = b.muscles.size > 1
            return when {
                ka == Kind.PUSH && kb == Kind.PULL || ka == Kind.PULL && kb == Kind.PUSH -> ca == cb // базовое с базовым, изоляция с изоляцией
                ka == Kind.ARMS_FLEX && kb == Kind.ARMS_EXT || ka == Kind.ARMS_EXT && kb == Kind.ARMS_FLEX -> true
                ka == Kind.LEGS && ca -> kb == Kind.OTHER && !cb
                kb == Kind.LEGS && cb -> ka == Kind.OTHER && !ca
                ka == Kind.LEGS && kb == Kind.OTHER || ka == Kind.OTHER && kb == Kind.LEGS -> true
                ka == Kind.OTHER && kb == Kind.OTHER -> main(a) != main(b)
                else -> false
            }
        }
        val out = mutableListOf<PlanItem>()
        val used = BooleanArray(ordered.size)
        var label = 'A'
        for (i in ordered.indices) {
            if (used[i]) continue
            used[i] = true
            val a = p.ex(ordered[i].ex)!!
            val j = (i + 1 until ordered.size).firstOrNull { !used[it] && fits(a, p.ex(ordered[it].ex)!!) }
            if (j == null) { out += ordered[i]; continue }
            used[j] = true
            out += ordered[i].copy(pair = label.toString()); out += ordered[j].copy(pair = label.toString())
            label++
        }
        return out
    }

    /** Короткое описание дня: главные мышцы плана. */
    fun summary(p: Program, plan: DayPlan): String {
        val m = HashMap<String, Int>()
        plan.items.forEach { i -> p.ex(i.ex)?.muscles?.filterValues { it >= 1.0 }?.keys?.forEach { k -> m[k] = (m[k] ?: 0) + i.sets } }
        return m.entries.sortedByDescending { it.value }.take(4)
            .joinToString(", ") { MUSCLES[it.key]?.substringBefore(" (") ?: it.key }
    }
}
