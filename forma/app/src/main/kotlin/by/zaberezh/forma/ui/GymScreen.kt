@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package by.zaberezh.forma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.ai.Claude
import by.zaberezh.forma.core.gym.DayPlan
import by.zaberezh.forma.core.gym.Exercise
import by.zaberezh.forma.core.gym.FOCUS
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.PROGRAM
import by.zaberezh.forma.core.gym.PlanItem
import by.zaberezh.forma.core.gym.Planner
import by.zaberezh.forma.core.gym.Program
import by.zaberezh.forma.core.gym.SetLog
import by.zaberezh.forma.core.gym.Target
import by.zaberezh.forma.core.gym.VISIT
import by.zaberezh.forma.core.gym.WORKOUT
import by.zaberezh.forma.core.gym.Workout
import by.zaberezh.forma.core.gym.aiPlanPrompt
import by.zaberezh.forma.core.gym.nextTarget
import by.zaberezh.forma.core.gym.parseProgram
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.store.JSON
import by.zaberezh.forma.core.store.ZONE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

const val ACTIVE = "gym.active"

private const val SUPERSET_HOW = "A1/A2 — суперсет: подход первого → 60–90 с → подход второго → 60–90 с → снова первое. " +
    "У каждой мышцы выходит 2,5–3 мин отдыха, рост тот же, тренировка на 30–40% короче. Выключить — в настройках."

/** Метки A1, A2, B1… по парам плана. */
fun pairLabels(items: List<PlanItem>): Map<String, String> {
    val n = HashMap<String, Int>()
    return items.filter { it.pair != null }.associate { i -> val k = (n[i.pair!!] ?: 0) + 1; n[i.pair] = k; i.ex to "${i.pair}$k" }
}
private val DM = DateTimeFormatter.ofPattern("d MMM", RU)
private val DMHM = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)
private val DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)

@Composable
fun GymScreen() {
    val ctx = rememberCtx()
    val active = ctx.store.kvGet(ACTIVE)
    if (active != null && ctx.store.get(active) != null) WorkoutScreen(ctx, active) else GymHome(ctx)
}

/** Начать тренировку сегодня: план дня фиксируется, дальше меняется только руками. */
fun startWorkout(ctx: Ctx) {
    val s = ctx.store
    if (GymModule.storedPlan(s, ctx.today) == null) GymModule.savePlan(s, GymModule.planFor(ctx, ctx.today))
    val now = System.currentTimeMillis()
    val e = WORKOUT.save(s, Workout(ctx.today.toString(), now), ts = now)
    s.kvPut(ACTIVE, e.id)
}

@Composable
private fun GymHome(ctx: Ctx) {
    var sel by remember { mutableStateOf(ctx.today) }
    val s = ctx.store
    Screen {
        item { WeekCalendar(ctx, sel) { sel = it } }
        item { DayPlanBlock(ctx, sel) }
        item { ExerciseBase(ctx) }
        item {
            val list = GymModule.workouts(s).takeLast(8).reversed()
            Block("История тренировок") {
                if (list.isEmpty()) Muted("Пока пусто")
                list.forEach { (e, w) ->
                    val min = w.end?.let { (it - w.start) / 60_000 }
                    Line {
                        Column(Modifier.weight(1f)) {
                            Text(DAY.format(e.day), style = MaterialTheme.typography.bodyLarge)
                            Muted("подходов ${w.sets.size}" + (min?.let { " · $it мин" } ?: ""))
                        }
                        DeleteButton("тренировку") { s.delete(e.id) }
                    }
                }
            }
        }
        item {
            Block("Визиты по геолокации") {
                val names = ctx.settings.gyms.associate { it.id to it.name.substringBefore(" (") }
                val v = VISIT.all(s).takeLast(8).reversed()
                if (v.isEmpty()) Muted("Пока нет. Нужно разрешение геолокации «Разрешить всегда» (Настройки).")
                v.forEach { (_, x) ->
                    Stat(DMHM.format(Instant.ofEpochMilli(x.start).atZone(ZONE)) + " · " + (names[x.gym] ?: x.gym),
                        "${x.minutes} мин", if (x.minutes >= ctx.settings.minVisitMin) C.good else C.muted)
                }
            }
        }
        item { BackupBlock(GymModule.program(s), ctx) }
    }
}

/** Календарь недели: ✓ — сделано, «зал» — по плану; нажми на день — его план ниже. */
@Composable
private fun WeekCalendar(ctx: Ctx, sel: LocalDate, onSelect: (LocalDate) -> Unit) {
    val mon = ctx.today.with(DayOfWeek.MONDAY)
    val wk = GymModule.week(ctx)
    val trained = GymModule.trainedDays(ctx, mon, mon.plusDays(6))
    val target = ctx.settings.sessionsPerWeek
    Block("Неделя", trailing = { Pill("${wk.done} / $target", if (wk.done >= target) C.good else C.accent) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (0L..6L).map { mon.plusDays(it) }.forEach { d ->
                val done = d in trained
                val gym = d in wk.plan
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (d == sel) C.accent.copy(alpha = 0.14f) else C.bg)
                        .then(if (d == sel) Modifier.border(1.dp, C.accent, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable { onSelect(d) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, RU), style = MaterialTheme.typography.labelSmall,
                        color = if (d == ctx.today) C.accent else C.muted)
                    Text("${d.dayOfMonth}", style = MaterialTheme.typography.titleMedium)
                    Text(when { done -> "✓"; gym -> "зал"; else -> "·" }, style = MaterialTheme.typography.labelSmall,
                        color = when { done -> C.good; gym -> C.accent; else -> C.muted })
                }
            }
        }
        Muted("Нажми на день — ниже его план. Там же можно сделать день днём зала или отдыха.")
        if (GymModule.threeInRow(ctx)) Text("* 3 дня подряд — не лучшая идея: мышцы не успевают восстановиться, особенно при недосыпе.",
            style = MaterialTheme.typography.bodySmall, color = C.warn)
    }
}

/** План выбранного дня: готов по умолчанию; можно убрать/добавить упражнение, выбрать акцент, пересобрать. */
@Composable
private fun DayPlanBlock(ctx: Ctx, date: LocalDate) {
    val s = ctx.store
    val scope = rememberCoroutineScope()
    val p = GymModule.program(s)
    val wk = GymModule.week(ctx)
    val trained = GymModule.trainedDays(ctx, date, date)
    val isGym = date in wk.plan
    var adding by remember(date) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var info by remember(date) { mutableStateOf<String?>(null) }
    var err by remember(date) { mutableStateOf<String?>(null) }
    val title = if (date == ctx.today) "Сегодня" else DAY.format(date).replaceFirstChar { it.uppercase() }

    Block(title, trailing = {
        when {
            date in trained -> Pill("сделано", C.good)
            isGym -> Pill("зал", C.accent)
            else -> Pill("отдых", C.muted)
        }
    }) {
        // прошедшие/выполненные дни — что было сделано
        if (date < ctx.today || date in trained) {
            val done = GymModule.sessions(s).filter { it.first == date }.flatMap { it.second }
            if (done.isEmpty()) Muted("Тренировки не было")
            done.groupBy { it.ex }.forEach { (id, l) -> Stat(p.ex(id)?.name ?: id, l.joinToString("  ") { "${it.w.r1()}×${it.r}" }) }
            if (date < ctx.today || date in trained) return@Block
        }
        if (!isGym && date != ctx.today) {
            Muted("День отдыха.")
            Secondary("Сделать днём зала", { err = GymModule.toggleDay(ctx, date) }, Modifier.fillMaxWidth())
            Err(err)
            return@Block
        }
        if (!isGym) Muted("По плану сегодня отдых, но потренироваться можно.")

        val plan = GymModule.planFor(ctx, date)
        fun save(pl: DayPlan) = GymModule.savePlan(s, pl.copy(source = if (pl.source == "claude") "claude" else "edited"))

        Text("Акцент (по желанию)", style = MaterialTheme.typography.labelLarge, color = C.muted)
        Buttons {
            FilterChip(selected = plan.focus == null, onClick = { GymModule.resetPlan(s, date) }, label = { Text("Авто") })
            FOCUS.forEach { (key, v) ->
                FilterChip(selected = plan.focus == key, onClick = { save(GymModule.planFor(ctx, date, key)) }, label = { Text(v.first) })
            }
        }
        val summary = Planner.summary(p, plan)
        if (summary.isNotEmpty()) Stat("Главное", summary)
        if (plan.note.isNotBlank()) Muted(plan.note)
        if (plan.source != "auto") Muted(if (plan.source == "claude") "Составлено Claude · «Авто» вернёт автоплан" else "Изменено вручную · «Авто» вернёт автоплан")

        val targets = GymModule.targets(s, plan)
        val labels = pairLabels(plan.items)
        if (plan.items.isEmpty()) Muted("База упражнений пуста — добавь упражнения ниже, и план соберётся сам.")
        if (plan.items.any { it.pair != null }) Muted(SUPERSET_HOW)
        plan.items.forEachIndexed { i, item ->
            val ex = p.ex(item.ex) ?: return@forEachIndexed
            val t = targets.firstOrNull { it.ex.id == item.ex }
            if (i > 0) HorizontalDivider(color = C.line)
            Line {
                labels[item.ex]?.let { Pill(it, C.accent) }
                Column(Modifier.weight(1f)) {
                    Text(ex.name, style = MaterialTheme.typography.bodyLarge)
                    Muted("${item.sets} подх. · " + (t?.weight?.let { "${it.r1()} кг × ${t.reps.joinToString(",")}" } ?: "подбор веса"))
                }
                Flat("−", { save(plan.copy(items = plan.items.map { if (it.ex == item.ex) it.copy(sets = (it.sets - 1).coerceAtLeast(1)) else it })) }, C.muted)
                Flat("+", { save(plan.copy(items = plan.items.map { if (it.ex == item.ex) it.copy(sets = (it.sets + 1).coerceAtMost(6)) else it })) }, C.muted)
                Flat("✕", { save(plan.copy(items = plan.items.filter { it.ex != item.ex })) }, C.bad)
            }
        }
        if (adding) {
            val rest = p.exercises.filter { e -> plan.items.none { it.ex == e.id } }
            if (rest.isEmpty()) Muted("Все упражнения из базы уже в плане")
            rest.forEach { e ->
                Text("+ ${e.name}", Modifier.fillMaxWidth().clickable {
                    save(plan.copy(items = plan.items + PlanItem(e.id, e.sets))); adding = false
                }.padding(vertical = 8.dp), color = C.accent)
            }
            Flat("Закрыть", { adding = false }, C.muted)
        }
        Buttons {
            if (!adding && p.exercises.isNotEmpty()) Secondary("+ Упражнение", { adding = true })
            Flat("Пересобрать", { GymModule.resetPlan(s, date) })
            Flat(if (busy) "Claude думает…" else "Через Claude", {
                val st = ctx.settings
                if (st.apiKey.isBlank()) { err = "API-ключ не задан (Настройки → Claude)"; return@Flat }
                if (busy || p.exercises.isEmpty()) return@Flat
                busy = true; err = null; info = null
                scope.launch {
                    val ai = Claude(st.apiKey, st.model, st.apiUrl)
                    val r = withContext(Dispatchers.IO) { runCatching { ai.planDay(METHOD, aiPlanPrompt(ctx, date, plan.focus)) } }
                    busy = false
                    r.onSuccess { (items, note) ->
                        val valid = items.filter { p.ex(it.first) != null }.distinctBy { it.first }.map { PlanItem(it.first, it.second) }
                        if (valid.isEmpty()) err = "Claude вернул пустой план"
                        else GymModule.savePlan(s, DayPlan(date.toString(), valid, plan.focus, "claude", note))
                        info = "Claude: ~${ai.used} токенов"
                    }.onFailure { err = Claude.explain(it) + " (~${ai.used} токенов)" }
                }
            })
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (date == ctx.today && plan.items.isNotEmpty()) Primary("Начать тренировку", { startWorkout(ctx) }, Modifier.fillMaxWidth())
        if (isGym && date != ctx.today) Flat("Сделать днём отдыха", { err = GymModule.toggleDay(ctx, date) }, C.muted)
        Note(info); Err(err)
    }
}

@Composable
private fun BackupBlock(p: Program, ctx: Ctx) {
    val c = LocalContext.current
    var edit by remember { mutableStateOf(false) }
    Block("Резервная копия базы") {
        if (!edit) Flat("Экспорт / импорт JSON", { edit = true }, C.muted)
        else {
            var text by remember { mutableStateOf(JSON.encodeToString(Program.serializer(), p)) }
            var err by remember { mutableStateOf<String?>(null) }
            Field("База (JSON)", text, { text = it }, number = false, lines = 6)
            Err(err)
            Buttons {
                Primary("Применить", { runCatching { parseProgram(text) }.onSuccess { PROGRAM.set(ctx.store, it); edit = false }.onFailure { err = it.message } })
                Secondary("Копировать", { c.copy(text) })
                Flat("Закрыть", { edit = false }, C.muted)
            }
        }
    }
}

@Composable
private fun WorkoutScreen(ctx: Ctx, id: String) {
    val s = ctx.store
    val e = s.get(id) ?: return
    val w = WORKOUT.decode(e)
    val p = GymModule.program(s)
    val date = runCatching { LocalDate.parse(w.day) }.getOrElse { e.day }
    val plan = GymModule.storedPlan(s, date) ?: GymModule.planFor(ctx, date)
    val setsPlanned = plan.items.associate { it.ex to it.sets }
    val ids = (plan.items.map { it.ex } + w.sets.map { it.ex }).distinct()
    val targets = remember(e.ts, ids, setsPlanned) {
        ids.mapNotNull(p::ex).associate { ex ->
            val x = ex.copy(sets = setsPlanned[ex.id] ?: ex.sets)
            ex.id to (x to nextTarget(x, GymModule.history(s, ex.id, e.ts).map { it.second }))
        }
    }
    var lastSet by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pick by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    fun save(nw: Workout) = WORKOUT.save(s, nw, ts = e.ts, id = id)
    fun addToPlan(exId: String) {
        val cur = GymModule.storedPlan(s, date) ?: plan
        val sets = GymModule.program(s).ex(exId)?.sets ?: 3
        if (cur.items.none { it.ex == exId }) GymModule.savePlan(s, cur.copy(items = cur.items + PlanItem(exId, sets), source = "edited"))
    }

    Screen {
        item {
            val done = w.sets.size
            val total = targets.values.sumOf { it.first.sets }
            Block("Тренировка · ${DM.format(date)}", trailing = { Pill("$done / $total подходов") }) {
                Line {
                    Column(Modifier.weight(1f)) {
                        Muted("Идёт")
                        Text("${(now - w.start) / 60_000} мин", style = MaterialTheme.typography.titleLarge)
                    }
                    Column(Modifier.weight(1f)) {
                        Muted("Отдых")
                        val r = if (lastSet > 0) (now - lastSet) / 1000 else 0L
                        Text(if (lastSet > 0) "${r / 60}:${"%02d".format(r % 60)}" else "—", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Line {
                    Primary("Завершить", { save(w.copy(end = System.currentTimeMillis())); s.kvPut(ACTIVE, null) }, Modifier.weight(1f))
                    Flat("Отменить", { cancel = true }, C.muted)
                }
            }
        }
        items(ids, key = { it }) { exId ->
            val pair = targets[exId] ?: return@items
            val item = plan.items.firstOrNull { it.ex == exId }
            val mate = item?.pair?.let { l -> plan.items.firstOrNull { it.pair == l && it.ex != exId } }?.let { p.ex(it.ex)?.name }
            ExerciseCard(pair.first, pair.second, w.sets.filter { it.ex == exId },
                superset = mate?.let { "${pairLabels(plan.items)[exId]} · суперсет с «$it»: подход → 60–90 с → подход второго" },
                onAdd = { set -> save(w.copy(sets = w.sets + set)); lastSet = System.currentTimeMillis() },
                onUndo = {
                    val i = w.sets.indexOfLast { it.ex == exId }
                    if (i >= 0) save(w.copy(sets = w.sets.filterIndexed { j, _ -> j != i }))
                })
        }
        item {
            when {
                creating -> ExerciseForm(ctx, null, onDone = { creating = false }, onSaved = { addToPlan(it) })
                !pick -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ids.isEmpty()) Muted("В плане пусто — создай упражнение: название, вес, повторы.")
                    Secondary("+ Упражнение из базы", { pick = true }, Modifier.fillMaxWidth(), enabled = p.exercises.any { it.id !in ids })
                    Secondary("+ Новое упражнение в базу", { creating = true }, Modifier.fillMaxWidth())
                }
                else -> Block("Добавить из базы") {
                    p.exercises.filter { it.id !in ids }.forEach { ex ->
                        Text(ex.name, Modifier.fillMaxWidth().clickable { addToPlan(ex.id); pick = false }.padding(vertical = 8.dp))
                    }
                    Flat("Закрыть", { pick = false }, C.muted)
                }
            }
        }
    }
    if (cancel) AlertDialog(
        onDismissRequest = { cancel = false },
        title = { Text("Отменить тренировку?") },
        text = { Text("Записанные подходы будут удалены.") },
        confirmButton = { TextButton({ cancel = false; s.delete(id); s.kvPut(ACTIVE, null) }) { Text("Удалить", color = C.bad) } },
        dismissButton = { TextButton({ cancel = false }) { Text("Назад") } },
        containerColor = C.cardHi,
    )
}

@Composable
private fun ExerciseCard(ex: Exercise, t: Target?, done: List<SetLog>, superset: String? = null, onAdd: (SetLog) -> Unit, onUndo: () -> Unit) {
    var wt by remember(ex.id, done.size) { mutableStateOf((done.lastOrNull()?.w ?: t?.weight)?.r1() ?: "") }
    var rp by remember(ex.id, done.size) { mutableStateOf((t?.reps?.getOrNull(done.size) ?: done.lastOrNull()?.r)?.toString() ?: "") }
    val complete = done.size >= ex.sets
    Block(ex.name, trailing = { Pill("${done.size}/${ex.sets}", if (complete) C.good else C.muted) }) {
        if (superset != null) Text(superset, style = MaterialTheme.typography.bodySmall, color = C.accent)
        Stat("Цель", t?.weight?.let { "${it.r1()} кг × ${t.reps.joinToString(", ")}" } ?: "подобрать вес")
        Muted("${ex.repMin}–${ex.repMax} повт. · в запасе ${ex.rir} · " + (if (superset != null) "между упражнениями пары 60–90 с" else "отдых ${ex.restSec / 60.0} мин") +
            (t?.note?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""))
        if (done.isNotEmpty()) Buttons {
            done.forEach { Pill("${it.w.r1()} × ${it.r}", C.good) }
        }
        Line {
            Field(if (ex.bw > 0) "Доп. вес" else "Вес", wt, { wt = it }, Modifier.weight(1f), suffix = "кг")
            Field("Повторы", rp, { rp = it }, Modifier.weight(1f))
        }
        Line {
            Primary("Записать подход", {
                val r = rp.num()?.toInt()
                if (r != null && r > 0) onAdd(SetLog(ex.id, wt.num() ?: 0.0, r))
            }, Modifier.weight(1f))
            if (done.isNotEmpty()) Flat("Отменить последний", onUndo, C.muted)
        }
    }
}
