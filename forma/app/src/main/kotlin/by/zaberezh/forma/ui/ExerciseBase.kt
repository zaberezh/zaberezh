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
import by.zaberezh.forma.core.gym.guessBodyweight
import by.zaberezh.forma.core.gym.guessMuscles
import by.zaberezh.forma.core.gym.guessStep
import by.zaberezh.forma.core.gym.parseReps
import by.zaberezh.forma.core.gym.remove
import by.zaberezh.forma.core.gym.upsert
import by.zaberezh.forma.core.pct
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.report.Checkup
import java.time.format.DateTimeFormatter

private val DM = DateTimeFormatter.ofPattern("d MMM", RU)

/** Методика — системный промпт для Claude (чекап, план дня). */
val METHOD: String by lazy { Checkup::class.java.getResource("/method.md")?.readText() ?: "" }

/** База упражнений: всё, что делаешь в зале, с текущей силой. План дня собирается отсюда. */
@Composable
fun ExerciseBase(ctx: Ctx) {
    val s = ctx.store
    val p = GymModule.program(s)
    var editing by remember { mutableStateOf<String?>(null) }   // "" — новое, id — правка
    var open by remember { mutableStateOf<String?>(null) }
    val bwKg = latestWeight(s) ?: 70.0
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        editing?.let { id -> ExerciseForm(ctx, p.ex(id), onDone = { editing = null }) }
        Block("База упражнений", trailing = { Pill("${p.exercises.size}", C.muted) }) {
            Muted("Всё, что тебе удобно делать в зале, с текущим весом и повторами. План на каждый день зала собирается отсюда.")
            if (editing == null) Primary("+ Новое упражнение", { editing = "" }, Modifier.fillMaxWidth())
            p.exercises.forEachIndexed { i, ex ->
                if (i > 0) HorizontalDivider(color = C.line)
                val tr = GymModule.trend(s, ex, ctx.today)
                Column(Modifier.fillMaxWidth().clickable { open = if (open == ex.id) null else ex.id }.padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Line {
                        Text(ex.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (tr.n > 0) Pill(tr.status, statusColor(tr.status))
                    }
                    Muted("сейчас ${GymModule.strength(s, ex)} · ${ex.sets}×${ex.repMin}–${ex.repMax}" +
                        (tr.pctWeek?.let { " · ${it.pct()}/нед" } ?: ""))
                    if (open == ex.id) {
                        val hist = GymModule.history(s, ex.id).takeLast(8).reversed()
                        if (hist.isEmpty()) Muted("Истории пока нет")
                        hist.forEach { (d, sets) ->
                            Stat(DM.format(d), sets.joinToString("  ") { "${it.w.r1()}×${it.r}" })
                        }
                        Line {
                            Flat("Изменить", { editing = ex.id })
                            DeleteButton("«${ex.name}» из базы") { PROGRAM.set(s, GymModule.program(s).remove(ex.id)) }
                        }
                    }
                }
            }
        }
    }
}

/** Форма упражнения: название, вес, повторы, подходы; мышца/шаг/вес тела угадываются по названию. */
@Composable
fun ExerciseForm(ctx: Ctx, ex: Exercise?, onDone: () -> Unit, onSaved: (String) -> Unit = {}) {
    val s = ctx.store
    val p = GymModule.program(s)
    var name by remember(ex) { mutableStateOf(ex?.name ?: "") }
    var weight by remember(ex) {
        mutableStateOf(ex?.let { e -> (GymModule.history(s, e.id).lastOrNull()?.second?.maxOf { it.w } ?: e.startWeight)?.r1() } ?: "")
    }
    var reps by remember(ex) { mutableStateOf(ex?.let { "${it.repMin}-${it.repMax}" } ?: "") }
    var sets by remember(ex) { mutableStateOf((ex?.sets ?: 3).toString()) }
    var muscles by remember(ex) { mutableStateOf(ex?.muscles?.filterValues { it >= 1.0 }?.keys ?: emptySet()) }
    var musclePicked by remember(ex) { mutableStateOf(ex != null) }
    var bw by remember(ex) { mutableStateOf(ex?.let { it.bw > 0 } ?: false) }
    var bwPicked by remember(ex) { mutableStateOf(ex != null) }
    var step by remember(ex) { mutableStateOf(ex?.step?.r1() ?: "") }
    var err by remember { mutableStateOf<String?>(null) }

    val guessed = guessMuscles(name).filterValues { it >= 1.0 }.keys
    val shownMuscles = if (musclePicked) muscles else guessed
    val shownBw = if (bwPicked) bw else guessBodyweight(name) > 0
    val range = parseReps(reps)

    Block(if (ex == null) "Новое упражнение" else "Изменить упражнение") {
        Field("Название", name, { name = it }, number = false)
        Line {
            Field(if (shownBw) "Доп. вес" else "Вес сейчас", weight, { weight = it }, Modifier.weight(1f), suffix = "кг")
            Field("Повторы", reps, { reps = it }, Modifier.weight(1f), number = false)
            Field("Подходы", sets, { sets = it }, Modifier.width(96.dp))
        }
        Muted(range?.let { (lo, hi) -> "Диапазон $lo–$hi: держишь вес, пока во всех подходах не будет $hi, потом +шаг и снова с $lo." }
            ?: "Повторы: сколько делаешь сейчас с этим весом (10) или диапазон (8-12).")
        Text("Основные мышцы — можно несколько" + if (!musclePicked && guessed.isNotEmpty()) " (угадано по названию)" else "",
            style = MaterialTheme.typography.labelLarge, color = C.muted)
        Buttons {
            MUSCLES.forEach { (id, label) ->
                FilterChip(selected = id in shownMuscles, onClick = {
                    muscles = if (id in shownMuscles) shownMuscles - id else shownMuscles + id; musclePicked = true
                }, label = { Text(label) })
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
                        name = name, weight = weight.num(), reps = reps, sets = sets.num()?.toInt() ?: 0,
                        muscles = if (musclePicked) shownMuscles else null, bodyweight = shownBw, step = step.num(),
                    ))
                }.onSuccess { np ->
                    PROGRAM.set(s, np)
                    onSaved(ex?.id ?: np.exercises.last().id)
                    onDone()
                }.onFailure { err = it.message }
            }, Modifier.weight(1f))
            Flat("Отмена", onDone, C.muted)
        }
    }
}
