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
 * В «Низ» — ещё руки (бицепс, трицепс) и предплечья: мелкие мышцы, восстанавливаются быстро, а при 3 тренировках
 * «Верх/Низ» иначе попадают в зал раз-два в неделю (в неделю с одним «Верхом» — один раз).
 */
val SPLITS: Map<String, Pair<String, List<DayType>>> = linkedMapOf(
    "ul" to ("Верх / Низ" to listOf(DayType("upper", "Верх", UPPER), DayType("lower", "Низ", LEGS + "abs" + "forearms" + "biceps" + "triceps" + "side_delts"))),
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

/** Крупные мышцы: им можно 2 упражнения за тренировку, когда объёма дня много (разные углы/растяжение → равномернее рост). */
private val BIG = setOf("chest", "back", "side_delts", "quads", "hams")

/** Визуальный приоритет (V-силуэт): средняя дельта, спина, грудь, руки. */
private val PRIORITY = mapOf("side_delts" to 1.3, "back" to 1.2, "chest" to 1.15, "forearms" to 1.15, "biceps" to 1.1, "triceps" to 1.1)

/**
 * Составитель тренировки дня — по недельному объёму, а не «закрыть самую большую нехватку»:
 * - у каждой мышцы недельная цель (подходы, середина диапазона); на сегодня — то, что осталось до цели на этой
 *   календарной неделе, делённое на оставшиеся в неделе тренировки с этой мышцей (пропуск не теряет объём);
 * - рукам, средней дельте и предплечьям нужна прямая работа: жим даёт трицепсу «полподхода», но без разгибаний
 *   трицепс растёт хуже — поэтому им засчитываются только свои упражнения, и они есть в каждой тренировке, где разрешены;
 * - 2 упражнения на мышцу — только когда на сегодня ей нужно больше ~6 подходов (иначе одно, а другое — в другой день недели);
 *   передняя дельта и ягодицы почти полностью закрываются жимами и приседами — отдельно берутся редко;
 * - упражнения одной мышцы чередуются по дням: первым идёт то, что дольше всего не делал (жим лёжа → наклонный → …);
 * - мышца, нагруженная вчера, почти не берётся (×0.3), позавчера — ×0.8 (восстановление 48–72 ч);
 * - не больше ~11 подходов на мышцу за тренировку — дальше прибавки почти нет (Pelland/Remmert 2025);
 * - бюджет подходов — от длительности тренировки (~3,6 мин на рабочий подход с отдыхом; 90 мин ≈ 25 подходов):
 *   не влезает — сначала срезаются подходы и упражнения у наименее важных мышц (икры, пресс, задняя дельта…).
 */
object Planner {
    /** Рабочих подходов за тренировку данной длительности. */
    fun budget(sessionMin: Int) = (sessionMin / 3.6).toInt().coerceIn(12, 30)
    const val MAX_SETS = 5          // подходов в одном упражнении
    const val PER_MUSCLE = 11.0     // подходов на мышцу за сессию

    /** Мышцы, которые почти целиком нагружают базовые (жим → передняя дельта, присед и тяги → ягодицы). */
    private val COVERED = setOf("front_delts", "glutes")

    /** Мышцы, которым засчитываются только свои упражнения (косвенная нагрузка из базовых не в счёт). */
    private val DIRECT = setOf("biceps", "triceps", "forearms", "side_delts")

    /** Важность мышц: в этом порядке набирается тренировка, с конца срезается при нехватке времени (V-силуэт и руки — вперёд). */
    private val KEEP = listOf("back", "side_delts", "chest", "triceps", "biceps", "quads", "hams", "forearms",
        "rear_delts", "front_delts", "glutes", "calves", "abs")
    private fun keep(m: String) = KEEP.indexOf(m).let { if (it < 0) KEEP.size else it }

    /** Засчитанные подходы на мышцу за один подход упражнения: прямой = 1, косвенный = доля (кроме [DIRECT]). */
    fun credit(e: Exercise): Map<String, Double> = e.muscles.mapNotNull { (m, k) ->
        val c = if (m in DIRECT) (if (k >= 1.0) 1.0 else 0.0) else k
        if (c > 0) m to c else null
    }.toMap()

    /** Засчитанные подходы на мышцу по дням (≥5 повторов). */
    fun muscleLog(p: Program, sessions: List<Pair<LocalDate, List<SetLog>>>): List<Pair<LocalDate, Map<String, Double>>> =
        sessions.map { (d, sets) ->
            val m = HashMap<String, Double>()
            sets.filter { it.r >= 5 }.forEach { st -> p.ex(st.ex)?.let(::credit)?.forEach { (k, v) -> m[k] = (m[k] ?: 0.0) + v } }
            d to m
        }

    /** План → засчитанные подходы на мышцу: для симуляции следующих дней недели. */
    fun planCredit(p: Program, plan: DayPlan): Map<String, Double> {
        val m = HashMap<String, Double>()
        plan.items.forEach { it -> p.ex(it.ex)?.let(::credit)?.forEach { (k, v) -> m[k] = (m[k] ?: 0.0) + v * it.sets } }
        return m
    }

    /** План → подходы на мышцу с косвенными (для показа и проверок). */
    fun planMuscles(p: Program, plan: DayPlan): Map<String, Double> {
        val m = HashMap<String, Double>()
        plan.items.forEach { it -> p.ex(it.ex)?.muscles?.forEach { (k, v) -> m[k] = (m[k] ?: 0.0) + v * it.sets } }
        return m
    }

    /**
     * @param log засчитанные подходы по дням (сделанное + запланированное до этой даты на этой неделе)
     * @param ahead типы тренировок этой недели после этой даты; null — по среднему для сплита
     */
    fun build(
        p: Program, date: LocalDate, log: List<Pair<LocalDate, Map<String, Double>>>,
        lastUsed: Map<String, LocalDate>, sessionsPerWeek: Int, focus: String? = null,
        day: DayType? = null, split: List<DayType> = listOfNotNull(day), sessionSets: Int = budget(75),
        ahead: List<DayType>? = null,
    ): DayPlan {
        if (p.exercises.isEmpty()) return DayPlan(date.toString(), emptyList(), focus, day = day?.id)
        val volume = DEFAULT_VOLUME + p.volume
        val focusSet = focus?.let { FOCUS[it]?.second } ?: emptySet()
        val allowed = (day?.muscles ?: volume.keys) + focusSet
        fun main(e: Exercise) = e.muscles.maxByOrNull { it.value }?.key ?: ""
        val byMuscle = p.exercises.groupBy(::main)

        // сколько тренировок с этой мышцей ещё будет на неделе, считая сегодняшнюю
        fun left(m: String): Double = if (ahead != null) 1.0 + ahead.count { m in it.muscles }
            else (if (split.isEmpty()) sessionsPerWeek.toDouble() else sessionsPerWeek.toDouble() * split.count { m in it.muscles } / split.size).coerceAtLeast(1.0)
        val monday = date.with(java.time.DayOfWeek.MONDAY)
        val thisWeek = log.filter { it.first >= monday && it.first < date }
        // нужно сегодня: остаток недельной цели на оставшиеся тренировки
        fun want(m: String): Double {
            val range = volume[m] ?: return 0.0
            // без недельного минимума (0–N): передняя дельта и ягодицы почти целиком закрываются жимами и приседами,
            // икры и пресс — немного каждую неделю
            var w = if (range[0] > 0) (range[0] + range[1]) / 2.0 else range[1] * (if (m in COVERED) 0.25 else 0.5)
            if (m in focusSet) w = maxOf(w * 1.4, range[1].toDouble(), 6.0)
            if (w <= 0) return 0.0
            val done = if (ahead != null) thisWeek.sumOf { it.second[m] ?: 0.0 } else 0.0
            var s = (w - done).coerceAtLeast(0.0) / left(m)
            val last = log.filter { it.first < date && (it.second[m] ?: 0.0) >= 3 }.maxOfOrNull { it.first }
            val gap = last?.let { ChronoUnit.DAYS.between(it, date) } ?: 99
            s *= when { gap <= 1 -> 0.3; gap == 2L -> 0.8; else -> 1.0 }
            if (focusSet.isNotEmpty() && m !in focusSet) s *= 0.6
            if (m in focusSet) s = maxOf(s, 6.0)              // акцент дня — минимум 2 упражнения по ~3 подхода
            return s.coerceAtMost(PER_MUSCLE)
        }

        // набор по важности: каждая мышца получает 1–2 упражнения; косвенное от уже выбранных вычитается
        data class Slot(val e: Exercise, var sets: Int, val m: String, val second: Boolean)
        val slots = mutableListOf<Slot>()
        val today = HashMap<String, Double>()
        for (m in allowed.sortedBy(::keep)) {
            val have = byMuscle[m].orEmpty().filter { e -> slots.none { it.e.id == e.id } }
            if (have.isEmpty()) continue
            val s = want(m) - (today[m] ?: 0.0)
            if (s < 1.5) continue
            // второе упражнение — только когда одному не вытянуть объём дня (> ~6 подходов): иначе одно,
            // а другой вариант — в другой день недели
            val n = when {
                m in focusSet && s > 8.5 && have.size >= 3 -> 3
                m in focusSet && have.size >= 2 -> 2
                s > 6.0 && (m in BIG || s > 6.5) && have.size >= 2 -> 2
                else -> 1
            }
            val per = (s / n).let { kotlin.math.round(it).toInt() }.coerceIn(2, 4)
            repeat(n) { i ->
                val pool = have.filter { e -> slots.none { it.e.id == e.id } }
                val first = slots.firstOrNull { it.m == m }?.e
                val pref = when {
                    first == null && m in BIG -> pool.filter(::isCompound).ifEmpty { pool }      // сначала базовое
                    first != null -> pool.filter { isCompound(it) != isCompound(first) }.ifEmpty { pool } // второе — другого типа
                    else -> pool
                }
                // по очереди: дольше всего не делал — первым (разные упражнения в разные дни недели)
                val e = pref.minWithOrNull(compareBy<Exercise>({ lastUsed[it.id] ?: LocalDate.MIN }, { p.exercises.indexOf(it) })) ?: return@repeat
                slots += Slot(e, per, m, i > 0)
                credit(e).forEach { (k, v) -> today[k] = (today[k] ?: 0.0) + v * per }
            }
        }

        // время: лишнее срезается с наименее важного (второе упражнение мышцы — менее важно первого)
        val maxEx = (sessionSets / 2.5).toInt().coerceAtLeast(4)
        fun rank(sl: Slot) = keep(sl.m) + if (sl.second) 4 else 0
        while (slots.size > maxEx) slots.remove(slots.maxByOrNull(::rank)!!)
        while (slots.sumOf { it.sets } > sessionSets) {
            val drop = slots.maxByOrNull(::rank) ?: break
            val cut = slots.filter { it.sets > 2 }.maxByOrNull(::rank)
            // необязательное (пресс, икры, вторые упражнения ног) — убрать; важное — сначала урезать подходы
            if (rank(drop) >= 10 || cut == null) slots.remove(drop) else cut.sets--
        }
        // время осталось — подходы важным мышцам, пока им ещё нужно (не больше 4 в упражнении)
        while (slots.sumOf { it.sets } < sessionSets - 1) {
            val got = HashMap<String, Double>()
            slots.forEach { sl -> credit(sl.e).forEach { (k, v) -> got[k] = (got[k] ?: 0.0) + v * sl.sets } }
            val add = slots.filter { it.sets < 4 && (got[it.m] ?: 0.0) < want(it.m) + 1 }.minByOrNull(::rank) ?: break
            add.sets++
        }
        return DayPlan(date.toString(), slots.map { PlanItem(it.e.id, it.sets) }, focus, day = day?.id)
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
    /**
     * Добавленное на тренировке упражнение — на своё место по группе мышц (порядок как у [arrange]):
     * сразу после того, за кем оно шло бы в упорядоченном плане. Остальные не двигаются — порядок дня не ломается.
     */
    fun insert(p: Program, items: List<PlanItem>, item: PlanItem, supersets: Boolean, focus: String? = null): List<PlanItem> {
        val arranged = arrange(p, items + item, supersets, focus)
        val i = arranged.indexOfFirst { it.ex == item.ex }
        if (i < 0) return items + item
        val prev = arranged.getOrNull(i - 1)?.ex
        val at = if (prev == null) 0 else items.indexOfFirst { it.ex == prev } + 1
        return items.toMutableList().apply { add(at.coerceIn(0, size), item) }
    }

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
