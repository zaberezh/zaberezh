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
    val day: String? = null,        // тип дня сплита (upper, lower, push…); null — старые планы
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

/** Тип дня сплита: какие мышцы в нём тренируются. */
data class DayType(val id: String, val title: String, val muscles: Set<String>)

private val UPPER = setOf("chest", "back", "front_delts", "side_delts", "rear_delts", "biceps", "triceps", "forearms")
private val LEGS = setOf("quads", "hams", "glutes", "calves")

/**
 * Сплиты: дни идут по кругу в порядке тренировок (пропуск не сбивает очередь).
 * Предплечья — в обоих днях «Верх/Низ» (акцент пользователя; мелкая мышца, восстанавливается быстро).
 */
val SPLITS: Map<String, Pair<String, List<DayType>>> = linkedMapOf(
    "ul" to ("Верх / Низ" to listOf(DayType("upper", "Верх", UPPER), DayType("lower", "Низ", LEGS + "abs" + "forearms"))),
    "ppl" to ("Жим / Тяга / Ноги" to listOf(
        DayType("push", "Жим", setOf("chest", "front_delts", "side_delts", "triceps")),
        DayType("pull", "Тяга", setOf("back", "rear_delts", "biceps", "forearms")),
        DayType("legs", "Ноги", LEGS + "abs"),
    )),
    "full" to ("Всё тело" to listOf(DayType("full", "Всё тело", UPPER + LEGS + "abs"))),
)

fun splitDays(id: String): List<DayType> = (SPLITS[id] ?: SPLITS.getValue("ul")).second

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
        day: DayType? = null, split: List<DayType> = listOfNotNull(day),
    ): DayPlan {
        if (p.exercises.isEmpty()) return DayPlan(date.toString(), emptyList(), focus, day = day?.id)
        val volume = DEFAULT_VOLUME + p.volume
        val focusSet = focus?.let { FOCUS[it]?.second } ?: emptySet()
        // мышцы дня: тип дня сплита (+ акцент, если выбран); без типа — всё тело
        val allowed = day?.let { it.muscles + focusSet }
        // сколько раз в неделю мышца попадает в тренировки при этом сплите
        fun perWeek(m: String) = if (split.isEmpty()) sessionsPerWeek.toDouble()
            else (sessionsPerWeek.toDouble() * split.count { m in it.muscles } / split.size).coerceAtLeast(1.0)
        val recent = log.filter { it.first < date && ChronoUnit.DAYS.between(it.first, date) <= 7 }
        val need = HashMap<String, Double>()
        volume.forEach { (m, range) ->
            val target = (range[0] + range[1]) / 2.0
            if (target <= 0 || (allowed != null && m !in allowed)) return@forEach
            val done = recent.sumOf { it.second[m] ?: 0.0 }
            val deficit = ((target - done) / target).coerceIn(0.0, 1.0)
            var n = target / perWeek(m) * (0.5 + deficit)
            val last = recent.filter { (it.second[m] ?: 0.0) >= 2 }.maxOfOrNull { it.first }
            val gap = last?.let { ChronoUnit.DAYS.between(it, date) } ?: 99
            n *= when { gap <= 1 -> 0.3; gap == 2L -> 0.8; else -> 1.0 }
            n *= PRIORITY[m] ?: 1.0
            if (focusSet.isNotEmpty()) n *= if (m in focusSet) 2.5 else 0.45
            need[m] = n
        }
        // мышцы с нулевым недельным минимумом (пресс, икры…) берутся при фокусе или если есть «запас» бюджета
        focusSet.forEach { m -> if ((need[m] ?: 0.0) < 2.0) need[m] = 3.0 }
        // в «Низ»/«Ноги» пресс с нулевым минимумом тоже берётся, если для него есть упражнение
        if (allowed != null && "abs" in allowed && (need["abs"] ?: 0.0) < 2.0) need["abs"] = 2.0

        val chosen = mutableListOf<Pair<Exercise, Int>>()
        var total = 0
        val perMain = HashMap<String, Int>()
        fun main(e: Exercise) = e.muscles.maxByOrNull { it.value }?.key ?: ""
        while (total < SESSION_SETS && chosen.size < MAX_EXERCISES) {
            val best = p.exercises.filter { e -> chosen.none { it.first.id == e.id } && (perMain[main(e)] ?: 0) < (if (main(e) in focusSet) 3 else 2) &&
                    (allowed == null || main(e) in allowed) }
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
        return DayPlan(date.toString(), chosen.map { PlanItem(it.first.id, it.second) }, focus, day = day?.id)
    }

    // ---------- порядок (и суперсеты — отключены по решению пользователя) ----------

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

    /** Блоки тренировки: ноги, жимы, тяги (+ мелкие мышцы своей зоны), пресс. */
    private enum class Block { LEGS, PUSH, PULL, CORE }

    private fun block(m: String) = when (m) {
        "quads", "hams", "glutes", "calves" -> Block.LEGS
        "chest", "front_delts", "side_delts", "triceps" -> Block.PUSH
        "back", "rear_delts", "biceps", "forearms" -> Block.PULL
        else -> Block.CORE
    }

    /** Зона внутри блока: дельты — одна зона (армейский и махи подряд), задняя дельта — со спиной. */
    private fun region(m: String) = when (m) {
        "front_delts", "side_delts" -> "delts"
        "rear_delts" -> "back"
        "quads", "hams", "glutes" -> "legs"
        else -> m
    }

    /** Мелкие мышцы — в конце своего блока, хват (предплечья) — самым последним. */
    private val TAIL = mapOf("triceps" to 1, "biceps" to 1, "calves" to 1, "forearms" to 2, "abs" to 1)

    /**
     * Порядок упражнений — блоками, без прыжков между мышцами:
     * ноги → жимы (грудь → дельты → трицепс) → тяги (спина → бицепс → предплечья) → пресс.
     * - Внутри зоны сначала базовое, потом изоляция; крупные мышцы раньше мелких (NSCA: многосуставные и крупные
     *   группы первыми). Мелкие мышцы — в конце своего блока, чтобы не утомить их до жимов и тяг, где они помогают.
     * - Блок с акцентом дня идёт первым: первым упражнениям достаётся больше повторов и прирост силы (Simão 2012);
     *   на рост мышц порядок почти не влияет (Nunes 2021). Пресс всегда в конце (держит корпус в базовых).
     * - Хват (предплечья) — последним, чтобы не подводил в тягах: блок тяг ставится в конец, а если акцент
     *   на спину вывел его вперёд — упражнения на предплечья переносятся в самый конец.
     * - Предутомление (изоляция перед базой) пользы не даёт (Trindade 2019).
     */
    fun arrange(p: Program, items: List<PlanItem>, supersets: Boolean, focus: String? = null): List<PlanItem> {
        val known = items.filter { p.ex(it.ex) != null }
        fun ex(i: PlanItem) = p.ex(i.ex)!!
        val focusSet = focus?.let { FOCUS[it]?.second } ?: emptySet()
        val lead = focusSet.filter { it != "forearms" && it != "abs" }.map(::block).toSet()
        val blocks = listOf(Block.LEGS, Block.PUSH, Block.PULL).sortedBy { if (it in lead) 0 else 1 } + Block.CORE
        val regionRank = known.groupBy { region(main(ex(it))) }.mapValues { (r, l) ->
            val hasComp = l.any { isCompound(ex(it)) }
            // зона с базовым — по главной мышце базового (жим лёжа раньше армейского), иначе по изоляции
            val pri = l.filter { !hasComp || isCompound(ex(it)) }.maxOf { PRIORITY[main(ex(it))] ?: 1.0 } +
                if (l.any { main(ex(it)) in focusSet }) 1.0 else 0.0
            (if (hasComp) 0.0 else 10.0) - pri + if (r == "calves") 20.0 else 0.0
        }
        var ordered = known.sortedWith(compareBy<PlanItem>(
            { blocks.indexOf(block(main(ex(it)))) },
            { TAIL[main(ex(it))] ?: 0 },
            { regionRank[region(main(ex(it)))] ?: 0.0 },
            { if (isCompound(ex(it))) 0 else 1 },
        )).map { it.copy(pair = null) }
        if (blocks.indexOf(Block.PULL) < 2) {
            val (grip, rest) = ordered.partition { main(ex(it)) == "forearms" }
            val core = rest.count { block(main(ex(it))) == Block.CORE }
            ordered = rest.dropLast(core) + grip + rest.takeLast(core)
        }
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
