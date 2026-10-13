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
    val lead: String? = null,       // "pull" — тяги в начале тренировки (чередуется с жимами по дням одного типа)
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

/** Тип дня сплита: мышцы, для которых это свой день (крупные — только в свой, мелкие при нехватке частоты — и в чужой). */
data class DayType(val id: String, val title: String, val muscles: Set<String>)

private val UPPER = setOf("chest", "back", "front_delts", "side_delts", "rear_delts", "biceps", "triceps", "forearms")
private val LEGS = setOf("quads", "hams", "glutes", "calves")

/**
 * Сплиты: дни идут по кругу в порядке тренировок (пропуск не сбивает очередь). При 3 тренировках «Верх/Низ» недели
 * чередуются: Верх–Низ–Верх, потом Низ–Верх–Низ — за две недели поровну.
 */
val SPLITS: Map<String, Pair<String, List<DayType>>> = linkedMapOf(
    "ul" to ("Верх / Низ" to listOf(DayType("upper", "Верх", UPPER), DayType("lower", "Низ", LEGS + "abs"))),
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
    e.muscles.size > 1 && mainMuscle(e) !in SMALL

/** Крупные мышцы: первым упражнением в тренировке им идёт базовое (жим, тяга, присед), изоляция — после. */
private val BIG = setOf("chest", "back", "side_delts", "quads", "hams")

/** Визуальный приоритет (V-силуэт): средняя дельта, спина, грудь, руки. */
private val PRIORITY = mapOf("side_delts" to 1.3, "back" to 1.2, "chest" to 1.15, "forearms" to 1.15, "biceps" to 1.1, "triceps" to 1.1)

/**
 * Составитель недели. Одно правило: **каждая мышца — хотя бы раз в неделю, два раза — если между тренировками
 * успевает восстановиться** (≥ 48 ч, то есть не два дня подряд). Чаще двух раз — нет: при том же недельном объёме
 * третий раз почти ничего не добавляет, а частота при равном объёме влияет слабо (Schoenfeld 2016, Pelland 2025).
 *
 * Все тренировки недели считаются вместе; сделанные и зафиксированные (правили руками, начатые) учитываются как есть:
 * 1. У мышцы недельная цель — середина диапазона подходов; сделанное на этой неделе вычитается.
 * 2. Тренировки для мышцы — её дни сплита («Верх» для спины и рук, «Низ» для ног). Мелким мышцам (руки, дельты,
 *    предплечья, икры, пресс) — и чужой день, если в своих два раза не набирается: в неделю «Низ–Верх–Низ» бицепс идёт
 *    в «Верх» и в один из «Низов». Дни выбираются так, чтобы между ними был отдых; из подходящих — ровнее по нагрузке.
 * 3. Объём мышцы делится поровну на её тренировки, не больше ~11 подходов за раз (дальше прибавки почти нет).
 * 4. Упражнения — по вариантам движения ([pattern]): два на мышцу в один день — разные варианты (вертикальная +
 *    горизонтальная тяга); во второй раз за неделю — вариант, которого на неделе было меньше (горизонтальный жим →
 *    наклонный, румынская тяга → сгибания ног); внутри варианта — то, что дольше всего не делал. Неделя к неделе
 *    раскладка повторяется, поэтому каждое упражнение регулярно возвращается и вес по нему растёт.
 * 5. Время (~3,6 мин на рабочий подход): не влезает — подходы урезаются соразмерно, каждой мышце дня — не меньше 2;
 *    не хватает и на это — мышца остаётся в своей второй тренировке недели. Осталось время — подходы тем, кто отстаёт
 *    от недельной цели.
 * Рукам, средней дельте и предплечьям засчитываются только их упражнения (жим трицепсу — не разгибания).
 * Передняя дельта, ягодицы, икры и пресс — без обязательного минимума: в остаток времени и с учётом того, что жимы,
 * приседы и тяги их уже нагрузили.
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

    /** Важность мышц: в этом порядке набирается неделя; при равенстве урезается менее важная (V-силуэт и руки — вперёд). */
    private val KEEP = listOf("back", "side_delts", "chest", "triceps", "biceps", "quads", "hams", "forearms",
        "rear_delts", "front_delts", "glutes", "calves", "abs")
    private fun keep(m: String) = KEEP.indexOf(m).let { if (it < 0) KEEP.size else it }

    /**
     * Засчитанные подходы на мышцу за один подход упражнения: прямой = 1, косвенный = доля. Мышцам из [DIRECT] —
     * только их собственные упражнения: тяга с отмеченными «спина + бицепс» бицепсу не засчитывается, иначе
     * сгибания на бицепс из плана пропадают, а тяги их не заменяют.
     */
    fun credit(e: Exercise): Map<String, Double> {
        val main = mainMuscle(e)
        return e.muscles.mapNotNull { (m, k) ->
            val c = if (m in DIRECT) (if (m == main) 1.0 else 0.0) else k
            if (c > 0) m to c else null
        }.toMap()
    }

    /** Засчитанные подходы на мышцу по дням (≥5 повторов). */
    fun muscleLog(p: Program, sessions: List<Pair<LocalDate, List<SetLog>>>): List<Pair<LocalDate, Map<String, Double>>> =
        sessions.map { (d, sets) ->
            val m = HashMap<String, Double>()
            sets.filter { it.r >= 5 }.forEach { st -> p.ex(st.ex)?.let(::credit)?.forEach { (k, v) -> m[k] = (m[k] ?: 0.0) + v } }
            d to m
        }

    /** План → засчитанные подходы на мышцу (зафиксированные планы недели учитываются в остальных днях). */
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

    /** Тренировка недели для составителя; fixed — уже составленный план (правили руками, начата, Claude): учитывается как есть. */
    data class Session(val date: LocalDate, val type: DayType?, val focus: String? = null, val fixed: List<PlanItem>? = null)

    /** Между тренировками одной мышцы — не меньше стольких дней: 2 — не два дня подряд (≥ 48 ч). */
    const val REST_DAYS = 2L
    /** Тренировок одной мышцы в неделю — не больше. */
    const val PER_WEEK = 2

    private class Slot(val e: Exercise, var sets: Int, val m: String, val second: Boolean)

    /**
     * Планы открытых (не сделанных и не зафиксированных) тренировок недели.
     * @param done сделанные тренировки (дата → засчитанные подходы на мышцу) этой и прошлой недели: объём и восстановление
     * @param doneSets подходы по упражнениям, сделанные на этой неделе (для чередования вариантов движения)
     * @param lastUsed последняя дата каждого упражнения
     */
    fun week(
        p: Program, sessions: List<Session>, done: List<Pair<LocalDate, Map<String, Double>>>,
        doneSets: Map<String, Double>, lastUsed: Map<String, LocalDate>, sessionSets: Int,
    ): Map<LocalDate, DayPlan> {
        val all = sessions.sortedBy { it.date }
        val open = all.filter { it.fixed == null }
        val slots = open.associate { it.date to mutableListOf<Slot>() }
        if (open.isNotEmpty() && p.exercises.isNotEmpty()) compose(p, all, open, slots, done, doneSets, lastUsed, sessionSets)
        return open.associate { s ->
            val day = slots.getValue(s.date)
            s.date to DayPlan(s.date.toString(), day.map { PlanItem(it.e.id, it.sets) }, s.focus, day = s.type?.id,
                note = if (day.isEmpty() && p.exercises.isNotEmpty())
                    "Мышцы этого дня ещё восстанавливаются после прошлых тренировок — лучше отдых или другой день сплита." else "")
        }
    }

    private fun compose(
        p: Program, all: List<Session>, open: List<Session>, slots: Map<LocalDate, MutableList<Slot>>,
        done: List<Pair<LocalDate, Map<String, Double>>>, doneSets: Map<String, Double>, lastUsed: Map<String, LocalDate>, sessionSets: Int,
    ) {
        val volume = DEFAULT_VOLUME + p.volume
        val byMuscle = p.exercises.groupBy(::mainMuscle)
        val monday = all.first().date.with(java.time.DayOfWeek.MONDAY)
        fun inWeek(d: LocalDate) = d >= monday && d < monday.plusDays(7)
        val focusOf = open.associate { it.date to (it.focus?.let { f -> FOCUS[f]?.second } ?: emptySet()) }
        fun focused(m: String) = focusOf.values.any { m in it }

        // сделанное и зафиксированное: объём недели, дни работы мышцы (для восстановления — и прошлой недели), подходы упражнений
        val base = HashMap<String, Double>()
        val worked = HashMap<String, MutableSet<LocalDate>>()
        fun account(d: LocalDate, c: Map<String, Double>) = c.forEach { (m, v) ->
            if (inWeek(d)) base[m] = (base[m] ?: 0.0) + v
            if (v >= 2.0) worked.getOrPut(m) { mutableSetOf() } += d
        }
        done.forEach { (d, c) -> account(d, c) }
        val exSets = HashMap(doneSets)
        all.filter { it.fixed != null }.forEach { s ->
            account(s.date, planCredit(p, DayPlan(s.date.toString(), s.fixed!!)))
            s.fixed.forEach { exSets[it.ex] = (exSets[it.ex] ?: 0.0) + it.sets }
        }
        val used = HashMap(lastUsed)

        /** Подходы мышцы за неделю: сделано + зафиксировано + составлено (с косвенными). */
        fun got(m: String) = (base[m] ?: 0.0) + slots.values.sumOf { day -> day.sumOf { sl -> (credit(sl.e)[m] ?: 0.0) * sl.sets } }
        fun target(m: String): Double {
            val range = volume[m] ?: return 0.0
            // без недельного минимума (0–N): передняя дельта и ягодицы почти целиком закрываются жимами и приседами,
            // икры и пресс — немного каждую неделю
            var w = if (range[0] > 0) (range[0] + range[1]) / 2.0 else range[1] * (if (m in COVERED) 0.25 else 0.5)
            if (focused(m)) w = maxOf(w * 1.4, range[1].toDouble(), 6.0)
            return w
        }
        fun optional(m: String) = (volume[m]?.getOrNull(0) ?: 0) == 0 && !focused(m)
        fun prio(m: String) = if (focused(m)) -1 else keep(m)
        fun load(d: LocalDate) = slots.getValue(d).sumOf { it.sets }
        fun room(d: LocalDate) = sessionSets - load(d)
        fun home(s: Session, m: String) = s.type == null || m in s.type.muscles
        fun gap(a: LocalDate, b: LocalDate) = kotlin.math.abs(ChronoUnit.DAYS.between(a, b))
        fun rested(d: LocalDate, others: Collection<LocalDate>) = others.all { it == d || gap(it, d) >= REST_DAYS }
        /** Самый короткий перерыв между днями работы мышцы (больше — лучше восстановление). */
        fun spread(dates: Collection<LocalDate>) = dates.distinct().sorted().zipWithNext { a, b -> gap(a, b) }.minOrNull() ?: 99L

        /** v подходов мышцы на тренировку → 1–3 упражнения разными вариантами движения. */
        fun pick(d: LocalDate, m: String, v: Double) {
            val day = slots.getValue(d)
            val have = byMuscle[m].orEmpty()
            var n = when { v <= 5.0 -> 1; v <= 9.0 -> 2; else -> 3 }
            if (m in focusOf.getValue(d)) n = maxOf(n, 2)                 // акцент дня — хотя бы 2 упражнения
            // v подходов — поровну на n упражнений, без лишних от округления: 11 → 4 + 4 + 3
            val total = kotlin.math.round(v).toInt().coerceIn(2 * n, MAX_SETS * n)
            repeat(n) { i ->
                val per = total / n + if (i < total % n) 1 else 0
                val pool = have.filter { e -> day.none { it.e.id == e.id } }
                val first = day.firstOrNull { it.m == m }?.e
                val todayPat = day.filter { it.m == m }.map { pattern(it.e) }.toSet()
                // сколько подходов этого варианта движения уже на неделе (сделано + зафиксировано + составлено)
                fun weekUses(e: Exercise) = have.filter { pattern(it) == pattern(e) }.sumOf { exSets[it.id] ?: 0.0 }
                val e = pool.minWithOrNull(compareBy<Exercise>(
                    // крупной мышце первым — базовое; у бицепса бедра сгибания ног — не добивка, а его вторая функция
                    { if (first == null && m in BIG && m != "hams" && !isCompound(it) && pool.any(::isCompound)) 1 else 0 },
                    // второе упражнение дня — другой вариант движения: вертикальная + горизонтальная тяга, а не две вертикальные
                    { if (pattern(it) in todayPat) 1 else 0 },
                    // вариант, которого на неделе было меньше, — первым: тренировки недели дополняют друг друга
                    { weekUses(it) },
                    // при прочих равных второе — другого типа: базовое + изоляция
                    { if (first != null && isCompound(it) == isCompound(first)) 1 else 0 },
                    // по очереди: дольше всего не делал — первым
                    { used[it.id] ?: LocalDate.MIN },
                    { p.exercises.indexOf(it) },
                )) ?: return@repeat
                day += Slot(e, per, m, i > 0)
                exSets[e.id] = (exSets[e.id] ?: 0.0) + per
                used[e.id] = d
            }
        }

        /** Мышца → её тренировки недели и подходы в каждой. spare — только в остаток времени (мышцы без минимума). */
        fun plan(m: String, spare: Boolean) {
            if (byMuscle[m].isNullOrEmpty()) return
            val forced = open.filter { m in focusOf.getValue(it.date) }   // акцент дня — мышца в этот день обязательно
            val rem = target(m) - got(m)
            if (rem < 1.5 && forced.isEmpty()) return
            val before = worked[m].orEmpty().count(::inWeek)             // сколько раз уже была на этой неделе
            var k = maxOf(PER_WEEK - before, forced.size)
            if (forced.isEmpty()) k = minOf(k, maxOf(1, (rem / 3).toInt()))   // осталось мало — один раз, а не два по подходу
            if (k <= 0) return
            val cands = open.filter { s -> s !in forced && (home(s, m) || m in SMALL) && (!spare || room(s.date) >= 2) }
            val busy = worked[m].orEmpty() + forced.map { it.date }
            var chosen: List<Session> = emptyList()
            for (n in (k - forced.size) downTo 1) {
                chosen = subsets(cands, n).filter { c -> c.all { s -> rested(s.date, busy + c.map { it.date }) } }
                    .minWithOrNull(compareBy<List<Session>>(
                        { c -> c.count { !home(it, m) } },                     // свой день лучше чужого
                        { c -> c.sumOf { load(it.date) } },                    // ровнее по тренировкам недели
                        { c -> -spread(c.map { it.date } + busy) },            // дальше друг от друга и от прошлой работы
                        { c -> c.first().date },
                    )) ?: emptyList()
                if (chosen.isNotEmpty()) break
            }
            chosen = forced + chosen
            // хотя бы раз в неделю: восстановиться не успевает нигде — всё равно в свой день, где отдых дольше
            if (chosen.isEmpty() && before == 0 && !spare)
                chosen = cands.filter { home(it, m) }.maxByOrNull { s -> worked[m].orEmpty().minOfOrNull { gap(it, s.date) } ?: 99L }
                    ?.let(::listOf).orEmpty()
            if (chosen.isEmpty()) return
            val per = (maxOf(rem, 0.0) / chosen.size).coerceIn(2.0, PER_MUSCLE)
            chosen.sortedBy { it.date }.forEach { s ->
                var v = if (m in focusOf.getValue(s.date)) maxOf(per, 6.0) else per
                if (spare) v = minOf(v, room(s.date).toDouble())
                if (v < 2.0) return@forEach
                pick(s.date, m, v)
                worked.getOrPut(m) { mutableSetOf() } += s.date
            }
        }

        /**
         * Время: не влезает — сначала мышцы, у которых на неделе есть другая тренировка (если даже по 2 подхода не
         * помещаются), потом лишние вторые упражнения, потом подходы — соразмерно у всех, не меньше 2 на мышцу.
         */
        fun fit(d: LocalDate) {
            val day = slots.getValue(d)
            fun sets(m: String) = day.filter { it.m == m }.sumOf { it.sets }
            fun muscles() = day.map { it.m }.distinct()
            while (muscles().size * 2 > sessionSets) {
                val m = muscles().filter { mm -> worked[mm].orEmpty().any { it != d && inWeek(it) } }.maxByOrNull(::prio) ?: break
                day.removeAll { it.m == m }; worked[m]?.remove(d)
            }
            val maxEx = (sessionSets / 2.5).toInt().coerceAtLeast(5)
            while (day.size > maxEx) day.remove(day.filter { it.second }.maxByOrNull { prio(it.m) } ?: break)
            val want = muscles().associateWith(::sets)
            while (day.sumOf { it.sets } > sessionSets) {
                // у кого больше доля от запланированного — тот и теряет подход: урезка соразмерная, менее важные — первыми
                val m = muscles().filter { sets(it) > 2 }
                    .maxWithOrNull(compareBy({ sets(it).toDouble() / (want[it] ?: 1) }, { prio(it) })) ?: break
                val mine = day.filter { it.m == m }
                val sl = mine.filter { it.sets > 2 }.maxByOrNull { it.sets }
                if (sl != null) sl.sets-- else day.remove(mine.last())        // все по 2 — убираем второе упражнение
            }
        }

        /**
         * Осталось время — подход тем, кто сильнее отстаёт: сначала до недельной цели, потом до верха полезного диапазона
         * (не больше 5 в упражнении и ~11 на мышцу за тренировку). Мусорных подходов сверх диапазона — нет.
         */
        fun fill(d: LocalDate) {
            val day = slots.getValue(d)
            for (top in listOf(false, true)) while (room(d) >= 1) {
                fun goal(m: String) = if (top) maxOf(target(m), volume[m]?.getOrNull(1)?.toDouble() ?: 0.0) else target(m)
                val sl = day.filter { sl ->
                    sl.sets < MAX_SETS && day.filter { it.m == sl.m }.sumOf { it.sets } < PER_MUSCLE && got(sl.m) < goal(sl.m) - 0.5
                }.minWithOrNull(compareBy({ got(it.m) / goal(it.m) }, { prio(it.m) }, { it.second })) ?: break
                sl.sets++
            }
        }

        val order = volume.keys.sortedBy(::prio)
        order.filterNot(::optional).forEach { plan(it, spare = false) }
        open.forEach { fit(it.date) }
        order.filter(::optional).forEach { plan(it, spare = true) }
        open.forEach { fill(it.date) }
    }

    private fun <T> subsets(items: List<T>, k: Int): List<List<T>> {
        if (k == 0) return listOf(emptyList())
        if (items.size < k) return emptyList()
        val head = items.first(); val tail = items.drop(1)
        return subsets(tail, k - 1).map { listOf(head) + it } + subsets(tail, k)
    }

    // ---------- порядок (и суперсеты — отключены по решению пользователя) ----------

    private enum class Kind { PUSH, PULL, LEGS, ARMS_FLEX, ARMS_EXT, OTHER }

    private fun main(e: Exercise) = mainMuscle(e)

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
     * - Без акцента жимы и тяги чередуются по дням одного типа ([pullFirst]): в одном «Верхе» свежей приходит грудь,
     *   в следующем — спина; ни одна группа не остаётся всегда «второй» и не отстаёт в силе.
     * - Хват (предплечья) — последним, чтобы не подводил в тягах: блок тяг ставится в конец, а если акцент
     *   на спину вывел его вперёд — упражнения на предплечья переносятся в самый конец.
     * - Предутомление (изоляция перед базой) пользы не даёт (Trindade 2019).
     */
    /**
     * Добавленное на тренировке упражнение — на своё место по группе мышц (порядок как у [arrange]):
     * сразу после того, за кем оно шло бы в упорядоченном плане. Остальные не двигаются — порядок дня не ломается.
     */
    fun insert(p: Program, items: List<PlanItem>, item: PlanItem, supersets: Boolean, focus: String? = null, pullFirst: Boolean = false): List<PlanItem> {
        val arranged = arrange(p, items + item, supersets, focus, pullFirst)
        val i = arranged.indexOfFirst { it.ex == item.ex }
        if (i < 0) return items + item
        val prev = arranged.getOrNull(i - 1)?.ex
        val at = if (prev == null) 0 else items.indexOfFirst { it.ex == prev } + 1
        return items.toMutableList().apply { add(at.coerceIn(0, size), item) }
    }

    fun arrange(p: Program, items: List<PlanItem>, supersets: Boolean, focus: String? = null, pullFirst: Boolean = false): List<PlanItem> {
        val known = items.filter { p.ex(it.ex) != null }
        fun ex(i: PlanItem) = p.ex(i.ex)!!
        val focusSet = focus?.let { FOCUS[it]?.second } ?: emptySet()
        val lead = focusSet.filter { it != "forearms" && it != "abs" }.map(::block).toSet()
        // без акцента жимы и тяги через раз меняются местами: свежими — то грудь, то спина
        val base = if (pullFirst && lead.isEmpty()) listOf(Block.LEGS, Block.PULL, Block.PUSH) else listOf(Block.LEGS, Block.PUSH, Block.PULL)
        val blocks = base.sortedBy { if (it in lead) 0 else 1 } + Block.CORE
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

    /** Короткое описание дня: что реально тренируется (засчитанные подходы), до 5 мышц — бицепс с 2–3 подходами не теряется. */
    fun summary(p: Program, plan: DayPlan): String {
        val m = HashMap<String, Double>()
        plan.items.forEach { i -> p.ex(i.ex)?.let(::credit)?.filterValues { it >= 1.0 }?.forEach { (k, v) -> m[k] = (m[k] ?: 0.0) + v * i.sets } }
        return m.entries.sortedByDescending { it.value }.take(5)
            .joinToString(", ") { MUSCLES[it.key]?.substringBefore(" (") ?: it.key }
    }
}
