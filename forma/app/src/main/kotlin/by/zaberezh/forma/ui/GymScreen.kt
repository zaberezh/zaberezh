package by.zaberezh.forma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.gym.Exercise
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.PROGRAM
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
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter

private const val ACTIVE = "gym.active"
private val HM = DateTimeFormatter.ofPattern("dd.MM HH:mm")

@Composable
fun GymScreen() {
    val ctx = rememberCtx()
    val active = ctx.store.kvGet(ACTIVE)
    if (active != null && ctx.store.get(active) != null) WorkoutScreen(ctx, active) else GymHome(ctx)
}

@Composable
private fun GymHome(ctx: Ctx) {
    val s = ctx.store
    val p = GymModule.program(s)
    val next = GymModule.nextDay(s)
    var open by remember { mutableStateOf<String?>(null) }
    var programEdit by remember { mutableStateOf(false) }
    val c = LocalContext.current
    Screen {
        item {
            Block("Тренировка") {
                Text("Следующая по кругу: день ${next.name}")
                Line {
                    p.days.forEach { d ->
                        val start = {
                            val now = System.currentTimeMillis()
                            val e = WORKOUT.save(s, Workout(d.id, now), ts = now)
                            s.kvPut(ACTIVE, e.id)
                        }
                        if (d.id == next.id) Button(onClick = start) { Text("Начать ${d.name}") }
                        else OutlinedButton(onClick = start) { Text(d.name) }
                    }
                }
                val wk = GymModule.week(ctx)
                Muted("План недели: " + wk.plan.joinToString { it.dayOfWeek.value.let { n -> listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")[n - 1] } })
            }
        }
        item { Text("Упражнения (нажми — история)") }
        items(p.exercises.filter { ex -> p.days.any { ex.id in it.exercises } }, key = { it.id }) { ex ->
            val hist = GymModule.history(s, ex.id)
            val t = nextTarget(ex, hist.map { it.second })
            val tr = GymModule.trend(s, ex, ctx.today)
            Block {
                Text(ex.name, Modifier.clickable { open = if (open == ex.id) null else ex.id })
                Muted("След.: " + (t.weight?.let { "${it.r1()}×${t.reps.joinToString(",")}" } ?: "подбор") + " · ${t.note}")
                if (tr.n > 0) Muted("e1RM ${tr.last.r1()} кг, ${tr.status}" + (tr.pctWeek?.let { " (${it.pct()}/нед)" } ?: ""))
                if (open == ex.id) hist.takeLast(10).reversed().forEach { (d, sets) ->
                    val bw = ex.bw * (by.zaberezh.forma.core.body.latestWeight(s) ?: 70.0)
                    Text("$d: " + sets.joinToString { "${it.w.r1()}×${it.r}" } + "  (e1RM ${sets.maxOf { e1rm(it.w, it.r, bw) }.r1()})")
                }
            }
        }
        item {
            Block("Последние тренировки") {
                GymModule.workouts(s).takeLast(10).reversed().forEach { (e, w) ->
                    val min = w.end?.let { (it - w.start) / 60_000 }
                    Line {
                        Text("${e.day} · ${w.day} · подходов ${w.sets.size}" + (min?.let { " · $it мин" } ?: ""))
                        TextButton(onClick = { s.delete(e.id) }) { Text("удалить") }
                    }
                }
            }
        }
        item {
            Block("Визиты в зал (геолокация)") {
                val names = ctx.settings.gyms.associate { it.id to it.name }
                val v = VISIT.all(s).takeLast(10).reversed()
                if (v.isEmpty()) Muted("Пока нет. Нужны разрешения геолокации «всегда» (Настройки).")
                v.forEach { (e, x) ->
                    Text("${HM.format(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(x.start), by.zaberezh.forma.core.store.ZONE))} · ${x.minutes} мин · ${names[x.gym] ?: x.gym}" +
                        if (x.minutes >= ctx.settings.minVisitMin) " ✓" else "")
                }
            }
        }
        item {
            Block("Программа: ${p.name}") {
                if (!programEdit) OutlinedButton(onClick = { programEdit = true }) { Text("JSON: посмотреть / вставить новую") }
                else {
                    var text by remember { mutableStateOf(JSON.encodeToString(by.zaberezh.forma.core.gym.Program.serializer(), p)) }
                    var err by remember { mutableStateOf<String?>(null) }
                    Field("Программа (JSON)", text, { text = it }, number = false, lines = 6)
                    Err(err)
                    Line {
                        Button(onClick = {
                            runCatching { parseProgram(text) }.onSuccess { PROGRAM.set(s, it); programEdit = false }.onFailure { err = it.message }
                        }) { Text("Применить") }
                        OutlinedButton(onClick = { c.copy(text) }) { Text("Копировать") }
                        TextButton(onClick = { PROGRAM.set(s, defaultProgram()); programEdit = false }) { Text("Сброс") }
                    }
                }
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
    val targets = remember(e.ts, ids) { ids.mapNotNull(p::ex).associate { ex -> ex.id to nextTarget(ex, GymModule.history(s, ex.id, e.ts).map { it.second }) } }
    var lastSet by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pick by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    fun save(nw: Workout) = WORKOUT.save(s, nw, ts = e.ts, id = id)

    Screen {
        item {
            Block("День ${w.day} · ${(now - w.start) / 60_000} мин") {
                if (lastSet > 0) { val r = (now - lastSet) / 1000; Text("Отдых: ${r / 60}:${"%02d".format(r % 60)}") }
                Line {
                    Button(onClick = { save(w.copy(end = System.currentTimeMillis())); s.kvPut(ACTIVE, null) }) { Text("Завершить") }
                    TextButton(onClick = { s.delete(id); s.kvPut(ACTIVE, null) }) { Text("Отменить") }
                }
            }
        }
        items(ids, key = { it }) { exId ->
            val ex = p.ex(exId) ?: return@items
            ExerciseCard(ex, targets[exId], w.sets.filter { it.ex == exId },
                onAdd = { set -> save(w.copy(sets = w.sets + set)); lastSet = System.currentTimeMillis() },
                onUndo = { val i = w.sets.indexOfLast { it.ex == exId }; if (i >= 0) save(w.copy(sets = w.sets.filterIndexed { j, _ -> j != i })) })
        }
        item {
            if (!pick) OutlinedButton(onClick = { pick = true }) { Text("+ упражнение не по плану") }
            else Block("Выбери") {
                p.exercises.filter { it.id !in ids }.forEach { ex ->
                    Text(ex.name, Modifier.clickable { extra = extra + ex.id; pick = false })
                }
            }
        }
    }
}

@Composable
private fun ExerciseCard(ex: Exercise, t: Target?, done: List<SetLog>, onAdd: (SetLog) -> Unit, onUndo: () -> Unit) {
    var wt by remember(ex.id, done.size) { mutableStateOf((done.lastOrNull()?.w ?: t?.weight)?.r1() ?: "") }
    var rp by remember(ex.id, done.size) { mutableStateOf((t?.reps?.getOrNull(done.size) ?: done.lastOrNull()?.r)?.toString() ?: "") }
    Block(ex.name) {
        Muted("Цель: " + (t?.weight?.let { "${it.r1()} × ${t.reps.joinToString(",")}" } ?: "подбери вес") +
            " · ${ex.repMin}–${ex.repMax}, в запасе ${ex.rir} · отдых ${ex.restSec / 60.0} мин")
        if (t != null && t.note.isNotEmpty()) Muted(t.note)
        if (done.isNotEmpty()) Text(done.joinToString("   ") { "${it.w.r1()}×${it.r}" } + "   (${done.size}/${ex.sets})")
        Line {
            Field(if (ex.bw > 0) "доп. кг" else "кг", wt, { wt = it }, Modifier.weight(1f))
            Field("повт.", rp, { rp = it }, Modifier.weight(1f))
            Button(onClick = { val w = wt.num() ?: 0.0; val r = rp.num()?.toInt(); if (r != null && r > 0) onAdd(SetLog(ex.id, w, r)) }) { Text("+") }
            if (done.isNotEmpty()) TextButton(onClick = onUndo) { Text("↶") }
        }
    }
}
