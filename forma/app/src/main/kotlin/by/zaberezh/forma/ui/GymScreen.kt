package by.zaberezh.forma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.body.latestWeight
import by.zaberezh.forma.core.gym.Exercise
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.PROGRAM
import by.zaberezh.forma.core.gym.Program
import by.zaberezh.forma.core.gym.SetLog
import by.zaberezh.forma.core.gym.Target
import by.zaberezh.forma.core.gym.VISIT
import by.zaberezh.forma.core.gym.WORKOUT
import by.zaberezh.forma.core.gym.Workout
import by.zaberezh.forma.core.gym.defaultProgram
import by.zaberezh.forma.core.gym.e1rm
import by.zaberezh.forma.core.gym.nextTarget
import by.zaberezh.forma.core.gym.parseProgram
import by.zaberezh.forma.core.pct
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.store.JSON
import by.zaberezh.forma.core.store.ZONE
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.format.DateTimeFormatter

const val ACTIVE = "gym.active"
private val DM = DateTimeFormatter.ofPattern("d MMM", RU)
private val DMHM = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)
private val WD = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

@Composable
fun GymScreen() {
    val ctx = rememberCtx()
    val active = ctx.store.kvGet(ACTIVE)
    if (active != null && ctx.store.get(active) != null) WorkoutScreen(ctx, active) else GymHome(ctx)
}

fun startWorkout(ctx: Ctx, day: String) {
    val now = System.currentTimeMillis()
    val e = WORKOUT.save(ctx.store, Workout(day, now), ts = now)
    ctx.store.kvPut(ACTIVE, e.id)
}

@Composable
private fun GymHome(ctx: Ctx) {
    val s = ctx.store
    val p = GymModule.program(s)
    val next = GymModule.nextDay(s)
    val wk = GymModule.week(ctx)
    Screen {
        item {
            Block("Следующая тренировка", trailing = { Pill("день ${next.name}") }) {
                GymModule.targets(s, next).forEach { t ->
                    Stat(t.ex.name, t.weight?.let { "${it.r1()} × ${t.reps.joinToString(",")}" } ?: "подбор")
                }
                Primary("Начать день ${next.name}", { startWorkout(ctx, next.id) }, Modifier.fillMaxWidth())
                Buttons {
                    Muted("Другой день:", Modifier.padding(top = 12.dp))
                    p.days.filter { it.id != next.id }.forEach { d -> Secondary(d.name, { startWorkout(ctx, d.id) }) }
                }
                Muted("План недели: " + wk.plan.joinToString(" · ") { WD[it.dayOfWeek.value - 1] } + " (перенести — на экране «Сегодня»)")
            }
        }
        item { ProgramEditor(ctx) }
        item {
            val list = GymModule.workouts(s).takeLast(8).reversed()
            Block("История тренировок") {
                if (list.isEmpty()) Muted("Пока пусто")
                list.forEach { (e, w) ->
                    val min = w.end?.let { (it - w.start) / 60_000 }
                    Line {
                        Column(Modifier.weight(1f)) {
                            Text("${DM.format(e.day)} · день ${w.day}", style = MaterialTheme.typography.bodyLarge)
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
        item { ProgramBlock(p, ctx) }
    }
}

@Composable
private fun ProgramBlock(p: Program, ctx: Ctx) {
    val c = LocalContext.current
    var edit by remember { mutableStateOf(false) }
    Block("Программа: копия") {
        if (!edit) Flat("Экспорт / импорт JSON (для переноса)", { edit = true }, C.muted)
        else {
            var text by remember { mutableStateOf(JSON.encodeToString(Program.serializer(), p)) }
            var err by remember { mutableStateOf<String?>(null) }
            Field("Программа (JSON)", text, { text = it }, number = false, lines = 6)
            Err(err)
            Buttons {
                Primary("Применить", { runCatching { parseProgram(text) }.onSuccess { PROGRAM.set(ctx.store, it); edit = false }.onFailure { err = it.message } })
                Secondary("Копировать", { c.copy(text) })
                Flat("Стандартная", { PROGRAM.set(ctx.store, defaultProgram()); edit = false }, C.muted)
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
    val planned = p.day(w.day)?.exercises ?: emptyList()
    var extra by remember { mutableStateOf(listOf<String>()) }
    val ids = (planned + w.sets.map { it.ex } + extra).distinct()
    val targets = remember(e.ts, ids) {
        ids.mapNotNull(p::ex).associate { ex -> ex.id to nextTarget(ex, GymModule.history(s, ex.id, e.ts).map { it.second }) }
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

    Screen {
        item {
            val done = w.sets.size
            val total = ids.mapNotNull(p::ex).sumOf { it.sets }
            Block("День ${w.day}", trailing = { Pill("$done / $total подходов") }) {
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
            val ex = p.ex(exId) ?: return@items
            ExerciseCard(ex, targets[exId], w.sets.filter { it.ex == exId },
                onAdd = { set -> save(w.copy(sets = w.sets + set)); lastSet = System.currentTimeMillis() },
                onUndo = {
                    val i = w.sets.indexOfLast { it.ex == exId }
                    if (i >= 0) save(w.copy(sets = w.sets.filterIndexed { j, _ -> j != i }))
                })
        }
        item {
            when {
                creating -> ExerciseForm(ctx, null, w.day) { creating = false }
                !pick -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ids.isEmpty()) Muted("В дне ${w.day} пока нет упражнений — создай их: название, вес, повторы.")
                    Primary("+ Создать упражнение", { creating = true }, Modifier.fillMaxWidth())
                    if (p.exercises.any { it.id !in ids }) Secondary("+ Из программы (другой день)", { pick = true }, Modifier.fillMaxWidth())
                }
                else -> Block("Добавить из программы") {
                    p.exercises.filter { it.id !in ids }.forEach { ex ->
                        Text(ex.name, Modifier.fillMaxWidth().clickable { extra = extra + ex.id; pick = false }.padding(vertical = 8.dp))
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
private fun ExerciseCard(ex: Exercise, t: Target?, done: List<SetLog>, onAdd: (SetLog) -> Unit, onUndo: () -> Unit) {
    var wt by remember(ex.id, done.size) { mutableStateOf((done.lastOrNull()?.w ?: t?.weight)?.r1() ?: "") }
    var rp by remember(ex.id, done.size) { mutableStateOf((t?.reps?.getOrNull(done.size) ?: done.lastOrNull()?.r)?.toString() ?: "") }
    val complete = done.size >= ex.sets
    Block(ex.name, trailing = { Pill("${done.size}/${ex.sets}", if (complete) C.good else C.muted) }) {
        Stat("Цель", t?.weight?.let { "${it.r1()} кг × ${t.reps.joinToString(", ")}" } ?: "подобрать вес")
        Muted("${ex.repMin}–${ex.repMax} повт. · в запасе ${ex.rir} · отдых ${ex.restSec / 60.0} мин" +
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
