package by.zaberezh.forma.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.store.Store
import by.zaberezh.forma.core.study.Bsuir
import by.zaberezh.forma.core.study.Lab
import by.zaberezh.forma.core.study.LabTask
import by.zaberezh.forma.core.study.Study
import by.zaberezh.forma.core.study.TIMETABLE
import java.time.format.DateTimeFormatter

private val DM = DateTimeFormatter.ofPattern("d MMM", RU)

/**
 * Лабы: предмет, номер, задания (сделано / нет), своё название по желанию.
 * Прогресс лабы — доля сделанных заданий; сделанная лаба — зелёный кружок с ✓ и зачёркнутое название.
 */
@Composable
fun LabsScreen() {
    val ctx = rememberCtx()
    val s = ctx.store
    val labs = Study.labs(s)
    val active = labs.filter { !it.second.done }
    val done = labs.filter { it.second.done }
    var showDone by remember { mutableStateOf(false) }

    val homework = Study.open(s, ctx.today)
    Screen {
        if (homework.isNotEmpty()) item {
            Block("Домашка", trailing = { Pill("${homework.size}") }) {
                homework.forEachIndexed { i, (e, hw) ->
                    if (i > 0) HorizontalDivider(color = C.line)
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { Study.setHomeworkDone(s, e.id, !hw.done) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CheckCircle(hw.done, { Study.setHomeworkDone(s, e.id, !hw.done) }, 28.dp)
                        Column(Modifier.weight(1f)) {
                            Text(hw.text, style = MaterialTheme.typography.bodyLarge)
                            Muted("${hw.subject}${if (hw.type.isNotBlank()) " · ${hw.type}" else ""} · к ${DM.format(java.time.LocalDate.parse(hw.due))}")
                        }
                    }
                }
            }
        }
        item {
            Block("Лабы", trailing = { if (labs.isNotEmpty()) Pill("${done.size} из ${labs.size}") }) {
                if (labs.isEmpty()) Muted("Добавь лабу: предмет, номер и сколько в ней заданий. Задания отмечаются по одному — прогресс лабы считается сам.")
                else {
                    val tasks = active.sumOf { it.second.total }; val tasksDone = active.sumOf { it.second.doneCount }
                    Stat("Активных", "${active.size}")
                    if (tasks > 0) Stat("Заданий осталось", "${tasks - tasksDone} из $tasks")
                }
                NewLab(s)
            }
        }
        active.groupBy { it.second.subject }.forEach { (subject, list) ->
            item { Text(subject, style = MaterialTheme.typography.labelLarge, color = C.muted, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
            list.forEach { (e, lab) -> item(key = e.id) { LabCard(s, e.id, lab) } }
        }
        if (done.isNotEmpty()) {
            item { Flat(if (showDone) "Скрыть сделанные (${done.size})" else "Сделанные (${done.size})", { showDone = !showDone }, C.muted) }
            if (showDone) done.forEach { (e, lab) -> item(key = e.id) { LabCard(s, e.id, lab) } }
        }
    }
}

/** Форма новой лабы: предмет (из расписания или свой), номер, число заданий, название. */
@Composable
private fun NewLab(s: Store) {
    var open by remember { mutableStateOf(false) }
    if (!open) { Primary("+ Новая лаба", { open = true }, Modifier.fillMaxWidth().height(52.dp)); return }
    val subjects = Bsuir.subjects(TIMETABLE.get(s))
    var subject by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (subjects.isNotEmpty()) Buttons {
            subjects.take(12).forEach { sub ->
                FilterChip(selected = subject == sub, onClick = { subject = sub; number = Study.nextNumber(s, sub).toString() }, label = { Text(sub) })
            }
        }
        Field("Предмет", subject, { subject = it; if (it.isNotBlank()) number = Study.nextNumber(s, it.trim()).toString() }, number = false)
        Grid2(listOf("n", "t")) { k, m ->
            if (k == "n") Field("Номер лабы", number, { number = it.filter(Char::isDigit).take(3) }, m)
            else Field("Заданий", tasks, { tasks = it.filter(Char::isDigit).take(2) }, m)
        }
        Field("Название (по желанию)", title, { title = it }, number = false)
        val n = number.toIntOrNull()
        Line {
            Primary("Создать", {
                Study.addLab(s, subject, n ?: 1, tasks.toIntOrNull() ?: 0, title)
                subject = ""; number = ""; tasks = ""; title = ""; open = false
            }, Modifier.weight(1f), enabled = subject.isNotBlank() && n != null)
            Flat("Отмена", { open = false }, C.muted)
        }
        Muted("Задания назовутся «Задание 1…N» — переименуй любое, нажав на него в лабе.")
    }
}

/** Кольцо прогресса; лаба сделана — сплошной зелёный круг с ✓. */
@Composable
private fun ProgressRing(percent: Int, done: Boolean, size: Dp = 40.dp, onClick: () -> Unit) {
    val sweep by animateFloatAsState(percent * 3.6f, label = "ring")
    val accent = C.accent; val line = C.line
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (done) {
            Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(C.good), contentAlignment = Alignment.Center) {
                Text("✓", color = C.bg, fontWeight = FontWeight.Bold)
            }
        } else {
            Canvas(Modifier.size(size)) {
                val w = 4.dp.toPx()
                val box = Size(this.size.width - w, this.size.height - w)
                val tl = Offset(w / 2, w / 2)
                drawArc(line, 0f, 360f, false, tl, box, style = Stroke(w))
                drawArc(accent, -90f, sweep, false, tl, box, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text("$percent", style = MaterialTheme.typography.labelSmall, color = C.text)
        }
    }
}

@Composable
private fun LabCard(s: Store, id: String, lab: Lab) {
    var expanded by remember(id) { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    Block {
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Line {
                ProgressRing(lab.percent, lab.done) { Study.setLabDone(s, id, !lab.done) }
                Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(lab.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        textDecoration = if (lab.done) TextDecoration.LineThrough else null, color = if (lab.done) C.muted else C.text)
                    Muted(lab.subject + if (lab.total > 0) " · ${lab.doneCount}/${lab.total} заданий" else "")
                }
                Text("${lab.percent}%", style = MaterialTheme.typography.titleSmall, color = if (lab.done) C.good else C.accent,
                    modifier = Modifier.clickable { expanded = !expanded })
            }
            if (lab.total > 0) Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.line)) {
                Box(Modifier.fillMaxWidth(lab.percent / 100f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (lab.done) C.good else C.accent))
            }
            if (expanded) {
                lab.tasks.forEachIndexed { i, t -> TaskRow(t, { Study.toggleTask(s, id, i) }) {
                    rename = t.name to { v: String -> Study.updateLab(s, id) { l -> l.copy(tasks = l.tasks.mapIndexed { j, x -> if (j == i) x.copy(name = v) else x }) } }
                } }
                HorizontalDivider(color = C.line)
                Grid2(listOf(0, 1, 2, 3)) { k, m ->
                    when (k) {
                        0 -> Secondary("+ Задание", { Study.updateLab(s, id) { it.copy(tasks = it.tasks + LabTask("Задание ${it.tasks.size + 1}")) } }, m)
                        1 -> Secondary("− Задание", { Study.updateLab(s, id) { it.copy(tasks = it.tasks.dropLast(1)) } }, m, enabled = lab.tasks.isNotEmpty())
                        2 -> Secondary("Название", { rename = lab.title to { v: String -> Study.updateLab(s, id) { it.copy(title = v) } } }, m)
                        else -> Primary(if (lab.done) "В работу" else "Сдана", { Study.setLabDone(s, id, !lab.done) }, m)
                    }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DeleteButton("лабу") { s.delete(id) } }
            }
        }
    }
    rename?.let { (cur, save) ->
        var v by remember(cur) { mutableStateOf(cur) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Название") },
            text = { OutlinedTextField(v, { v = it }, singleLine = true, shape = RoundedCornerShape(12.dp)) },
            confirmButton = { TextButton({ save(v.trim()); rename = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ rename = null }) { Text("Отмена") } },
            containerColor = C.cardHi,
        )
    }
}

/** Задание: кружок слева (зелёный с ✓ — сделано), название зачёркивается; нажатие на название — переименовать. */
@Composable
private fun TaskRow(t: LabTask, onToggle: () -> Unit, onRename: () -> Unit) = androidx.compose.foundation.layout.Row(
    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggle).padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    CheckCircle(t.done, onToggle, 30.dp)
    Text(t.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
        textDecoration = if (t.done) TextDecoration.LineThrough else null, color = if (t.done) C.muted else C.text)
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onRename), contentAlignment = Alignment.Center) {
        Text("✎", color = C.muted, style = MaterialTheme.typography.titleMedium)
    }
}
