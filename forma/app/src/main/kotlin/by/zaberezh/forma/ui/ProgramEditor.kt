@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package by.zaberezh.forma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.body.latestWeight
import by.zaberezh.forma.core.gym.Exercise
import by.zaberezh.forma.core.gym.ExerciseInput
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.MUSCLES
import by.zaberezh.forma.core.gym.PROGRAM
import by.zaberezh.forma.core.gym.e1rm
import by.zaberezh.forma.core.gym.guessBodyweight
import by.zaberezh.forma.core.gym.guessMuscles
import by.zaberezh.forma.core.gym.guessStep
import by.zaberezh.forma.core.gym.parseReps
import by.zaberezh.forma.core.gym.remove
import by.zaberezh.forma.core.gym.upsert
import by.zaberezh.forma.core.pct
import by.zaberezh.forma.core.r1
import java.time.format.DateTimeFormatter

private val DM = DateTimeFormatter.ofPattern("d MMM", RU)

/** Что сейчас открыто в редакторе: новое упражнение (с днём) или правка существующего. */
private data class Editing(val id: String?, val day: String?)

/** Программа по дням: список упражнений, добавление, изменение, удаление — без JSON. */
@Composable
fun ProgramEditor(ctx: Ctx) {
    val s = ctx.store
    val p = GymModule.program(s)
    var editing by remember { mutableStateOf<Editing?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    val bwKg = latestWeight(s) ?: 70.0

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        editing?.let { e -> ExerciseForm(ctx, e.id?.let(p::ex), e.day) { editing = null } }
            ?: Primary("+ Новое упражнение", { editing = Editing(null, p.days.firstOrNull()?.id) }, Modifier.fillMaxWidth())
        if (p.exercises.isEmpty() && editing == null)
            Muted("Программа пустая. Нажми «+ Новое упражнение»: название, вес, повторы, подходы и день — остальное подставится само.")
        val groups = p.days.map { d -> d.name to d.exercises.mapNotNull(p::ex) } +
            listOfNotNull(p.exercises.filter { ex -> p.days.none { ex.id in it.exercises } }.takeIf { it.isNotEmpty() }?.let { "без дня" to it })
        groups.forEachIndexed { gi, (dayName, list) ->
            val dayId = p.days.getOrNull(gi)?.id
            Block(if (dayId != null) "День $dayName" else "Без дня", trailing = { Pill("${list.size} упр.", C.muted) }) {
                if (list.isEmpty()) Muted("Пусто")
                list.forEachIndexed { i, ex ->
                    if (i > 0) HorizontalDivider(color = C.line)
                    ExerciseRow(ctx, ex, open == ex.id, bwKg,
                        onToggle = { open = if (open == ex.id) null else ex.id },
                        onEdit = { editing = Editing(ex.id, null) },
                        onDelete = { PROGRAM.set(s, p.remove(ex.id)) })
                }
                if (dayId != null) Secondary("+ Добавить упражнение", { editing = Editing(null, dayId) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ExerciseRow(ctx: Ctx, ex: Exercise, open: Boolean, bwKg: Double, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val s = ctx.store
    val tr = GymModule.trend(s, ex, ctx.today)
    val hist = GymModule.history(s, ex.id)
    val t = by.zaberezh.forma.core.gym.nextTarget(ex, hist.map { it.second })
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Line {
            Text(ex.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (tr.n > 0) Pill(tr.status, statusColor(tr.status))
        }
        Muted("${ex.sets}×${ex.repMin}–${ex.repMax} · след.: " +
            (t.weight?.let { "${it.r1()} кг × ${t.reps.joinToString(",")}" } ?: "подбор веса") +
            (if (tr.n > 0) " · e1RM ${tr.last.r1()}" + (tr.pctWeek?.let { " (${it.pct()}/нед)" } ?: "") else ""))
        if (open) {
            hist.takeLast(8).reversed().forEach { (d, sets) ->
                Stat(DM.format(d), sets.joinToString("  ") { "${it.w.r1()}×${it.r}" } + "   e1RM ${sets.maxOf { e1rm(it.w, it.r, ex.bw * bwKg) }.r1()}")
            }
            if (hist.isEmpty()) Muted("Истории пока нет")
            Line {
                Flat("Изменить", onEdit)
                DeleteButton("«${ex.name}» из программы") { onDelete() }
            }
        }
    }
}

@Composable
fun ExerciseForm(ctx: Ctx, ex: Exercise?, preDay: String?, onDone: () -> Unit) {
    val s = ctx.store
    val p = GymModule.program(s)
    var name by remember(ex) { mutableStateOf(ex?.name ?: "") }
    var weight by remember(ex) { mutableStateOf(ex?.let { e -> (GymModule.history(s, e.id).lastOrNull()?.second?.maxOf { it.w } ?: e.startWeight)?.r1() } ?: "") }
    var reps by remember(ex) { mutableStateOf(ex?.let { "${it.repMin}-${it.repMax}" } ?: "") }
    var sets by remember(ex) { mutableStateOf((ex?.sets ?: 3).toString()) }
    var days by remember(ex) { mutableStateOf(ex?.let { e -> p.days.filter { e.id in it.exercises }.map { it.id }.toSet() } ?: setOfNotNull(preDay)) }
    var muscle by remember(ex) { mutableStateOf(ex?.muscles?.entries?.firstOrNull { it.value == 1.0 }?.key) }
    var musclePicked by remember(ex) { mutableStateOf(ex != null) }
    var bw by remember(ex) { mutableStateOf(ex?.let { it.bw > 0 } ?: false) }
    var bwPicked by remember(ex) { mutableStateOf(ex != null) }
    var step by remember(ex) { mutableStateOf(ex?.step?.r1() ?: "") }
    var err by remember { mutableStateOf<String?>(null) }

    // подсказки по названию, пока пользователь сам не выбрал
    val guessed = guessMuscles(name).entries.firstOrNull { it.value == 1.0 }?.key
    val shownMuscle = if (musclePicked) muscle else guessed
    val shownBw = if (bwPicked) bw else guessBodyweight(name) > 0
    val range = parseReps(reps)

    Block(if (ex == null) "Новое упражнение" else "Изменить упражнение") {
        Field("Название", name, { name = it }, number = false)
        Line {
            Field(if (shownBw) "Доп. вес" else "Вес", weight, { weight = it }, Modifier.weight(1f), suffix = "кг")
            Field("Повторы", reps, { reps = it }, Modifier.weight(1f), number = false)
            Field("Подходы", sets, { sets = it }, Modifier.width(96.dp))
        }
        Muted(range?.let { (lo, hi) -> "Диапазон $lo–$hi: держишь вес, пока во всех подходах не будет $hi, потом +шаг и снова с $lo." }
            ?: "Повторы: сколько делаешь сейчас (10) или диапазон (8-12).")
        Text("День", style = MaterialTheme.typography.labelLarge, color = C.muted)
        Buttons {
            p.days.forEach { d ->
                FilterChip(selected = d.id in days, onClick = { days = if (d.id in days) days - d.id else days + d.id }, label = { Text(d.name) })
            }
        }
        Text("Основная мышца" + if (!musclePicked && guessed != null) " (угадано по названию)" else "", style = MaterialTheme.typography.labelLarge, color = C.muted)
        Buttons {
            MUSCLES.forEach { (id, label) ->
                FilterChip(selected = shownMuscle == id, onClick = { muscle = id; musclePicked = true }, label = { Text(label) })
            }
        }
        Line {
            Text("С весом тела (подтягивания, брусья)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = shownBw, onCheckedChange = { bw = it; bwPicked = true })
        }
        Field("Шаг прибавки", step.ifEmpty { if (name.isNotBlank()) guessStep(name).r1() else "" }, { step = it }, suffix = "кг")
        Err(err)
        Line {
            Primary("Сохранить", {
                runCatching {
                    p.upsert(ex?.id, ExerciseInput(
                        name = name, weight = weight.num(), reps = reps, sets = sets.num()?.toInt() ?: 0, days = days,
                        muscle = shownMuscle, bodyweight = shownBw, step = step.num(),
                    ))
                }.onSuccess { PROGRAM.set(s, it); onDone() }.onFailure { err = it.message }
            }, Modifier.weight(1f))
            Flat("Отмена", onDone, C.muted)
        }
    }
}
