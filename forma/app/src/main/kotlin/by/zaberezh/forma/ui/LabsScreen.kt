package by.zaberezh.forma.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
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
 * Прогресс лабы — доля сделанных заданий. Сделанную ещё надо сдать (жёлтый ✓, «сдать»); сданная — зелёный ✓ и зачёркнута.
 */
@Composable
fun LabsScreen() {
    val ctx = rememberCtx()
    val s = ctx.store
    val labs = Study.labs(s)
    val active = labs.filter { it.second.stage == 0 }
    val toSubmit = labs.filter { it.second.stage == 1 }
    val done = labs.filter { it.second.stage == 2 }
    var showDone by remember { mutableStateOf(false) }
    // раскрытые лабы — на уровне экрана: отметка задания перестраивает список, но лаба остаётся открытой
    val open = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    val homework = Study.open(s, ctx.today)
    Screen {
        item { SessionBlock(s, ctx.today, labs.count { it.second.stage < 2 }) }
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
            Block("Лабы", trailing = { if (labs.isNotEmpty()) Pill("сдано ${done.size} из ${labs.size}", if (done.size == labs.size) C.good else C.accent) }) {
                if (labs.isEmpty()) Muted("Добавь лабу: предмет, номер и сколько в ней заданий. Задания отмечаются по одному — прогресс лабы считается сам.")
                else {
                    val tasks = active.sumOf { it.second.total }; val tasksDone = active.sumOf { it.second.doneCount }
                    Stat("В работе", "${active.size}")
                    if (toSubmit.isNotEmpty()) Stat("Сделаны — нужно сдать", "${toSubmit.size}", C.warn)
                    if (tasks > 0) Stat("Заданий осталось", "${tasks - tasksDone} из $tasks")
                }
                NewLab(s)
            }
        }
        active.groupBy { it.second.subject }.forEach { (subject, list) ->
            item { Text(subject, style = MaterialTheme.typography.labelLarge, color = C.muted, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
            list.forEach { (e, lab) -> item(key = e.id) { LabCard(s, e.id, lab, open[e.id] == true) { open[e.id] = it } } }
        }
        if (toSubmit.isNotEmpty()) {
            item { Text("Нужно сдать", style = MaterialTheme.typography.labelLarge, color = C.warn, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
            toSubmit.forEach { (e, lab) -> item(key = e.id) { LabCard(s, e.id, lab, open[e.id] == true) { open[e.id] = it } } }
        }
        if (done.isNotEmpty()) {
            item { Flat(if (showDone) "Скрыть сданные (${done.size})" else "Сданные (${done.size})", { showDone = !showDone }, C.muted) }
            if (showDone) done.forEach { (e, lab) -> item(key = e.id) { LabCard(s, e.id, lab, open[e.id] == true) { open[e.id] = it } } }
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

/** Кольцо прогресса; сделана, но не сдана — жёлтый круг с ✓; сдана — сплошной зелёный круг с ✓. */
@Composable
private fun ProgressRing(percent: Int, stage: Int, size: Dp = 40.dp, onClick: () -> Unit) {
    val done = stage == 2
    val sweep by animateFloatAsState(percent * 3.6f, label = "ring")
    val accent = C.accent; val line = C.line
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (done) {
            Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(C.good), contentAlignment = Alignment.Center) {
                Text("✓", color = C.bg, fontWeight = FontWeight.Bold)
            }
        } else if (stage == 1) {
            Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(C.warn.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text("✓", color = C.warn, fontWeight = FontWeight.Bold)
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
private fun LabCard(s: Store, id: String, lab: Lab, expanded: Boolean, setExpanded: (Boolean) -> Unit) {
    var rename by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    Block {
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Line {
                ProgressRing(lab.percent, lab.stage) { Study.advance(s, id) }
                Column(Modifier.weight(1f).clickable { setExpanded(!expanded) }) {
                    Text(lab.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        textDecoration = if (lab.submitted) TextDecoration.LineThrough else null, color = if (lab.submitted) C.muted else C.text)
                    when (lab.stage) {
                        1 -> Text(lab.subject + " · сделана, нужно сдать", style = MaterialTheme.typography.bodySmall, color = C.warn)
                        2 -> Muted(lab.subject + " · сдана" + (lab.submittedOn?.let { " " + DM.format(java.time.LocalDate.parse(it)) } ?: "") +
                            (lab.mark?.let { " · отметка $it" } ?: ""))
                        else -> {
                            val due = lab.due?.let { java.time.LocalDate.parse(it) }
                            val late = due != null && due.isBefore(java.time.LocalDate.now())
                            Text(lab.subject + (if (lab.total > 0) " · ${lab.doneCount}/${lab.total} заданий" else "") +
                                (due?.let { " · срок ${DM.format(it)}" + if (late) " — просрочена" else "" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = if (late) C.bad else C.muted)
                        }
                    }
                }
                if (lab.stage == 1) Pill("сдать", C.warn)
                else Text("${lab.percent}%", style = MaterialTheme.typography.titleSmall, color = if (lab.submitted) C.good else C.accent,
                    modifier = Modifier.clickable { setExpanded(!expanded) })
            }
            if (lab.total > 0) Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.line)) {
                Box(Modifier.fillMaxWidth(lab.percent / 100f).height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(when (lab.stage) { 2 -> C.good; 1 -> C.warn; else -> C.accent }))
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
                        else -> when (lab.stage) {
                            0 -> Primary("Сделана", { Study.setLabDone(s, id, true) }, m)
                            1 -> Primary("Сдана", { Study.setSubmitted(s, id, true) }, m)
                            else -> Secondary("Не сдана", { Study.setSubmitted(s, id, false) }, m)
                        }
                    }
                }
                if (lab.stage == 1) Flat("Вернуть в работу", { Study.setLabDone(s, id, false) }, C.muted)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DeleteButton("лабу") { Study.deleteLab(s, id) } }
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

private val SDAY = DateTimeFormatter.ofPattern("d MMM, EE", RU)

/**
 * Сессия: сколько дней до неё (или «идёт»), экзамены и консультации из ИИС с днями до каждого,
 * и что ещё не сдано из лаб — сдать до сессии.
 */
@Composable
private fun SessionBlock(s: Store, today: java.time.LocalDate, labsLeft: Int) {
    val info = by.zaberezh.forma.core.study.Session.of(TIMETABLE.get(s), today)
    val left = info.daysLeft
    Block("Сессия", trailing = {
        when {
            info.running -> Pill("идёт", C.bad)
            left != null && left >= 0 -> Pill("через $left " + plural(left, "день", "дня", "дней"), if (left <= 14) C.warn else C.accent)
            else -> {}
        }
    }) {
        if (!info.known) Muted("Расписание сессии в ИИС ещё не опубликовано — экзамены появятся здесь сами после обновления расписания.")
        else {
            if (!info.running && left != null && left > 0) BigValue("$left", plural(left, "день", "дня", "дней"), "до сессии · экзаменов: ${info.exams.size}")
            if (info.running) BigValue("${info.exams.size}", plural(info.exams.size, "экзамен", "экзамена", "экзаменов"), "осталось сдать")
            info.upcoming.take(12).forEach { x ->
                val d = java.time.temporal.ChronoUnit.DAYS.between(today, x.day).toInt()
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.width(76.dp)) {
                        Text(SDAY.format(x.day), style = MaterialTheme.typography.bodyMedium, fontWeight = if (x.exam) FontWeight.SemiBold else null)
                        Muted(x.lesson.start)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(x.lesson.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (x.exam) FontWeight.Medium else null,
                            color = if (x.exam) C.text else C.muted)
                        Text(listOf(x.lesson.typeFull, x.lesson.rooms.joinToString(", ")).filter(String::isNotBlank).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = if (x.exam) C.bad else C.muted)
                    }
                    Text(if (d == 0) "сегодня" else if (d == 1) "завтра" else "$d дн.", style = MaterialTheme.typography.bodySmall,
                        color = if (d <= 3) C.warn else C.muted)
                }
            }
        }
        if (labsLeft > 0 && !info.running) Text("До сессии сдать лаб: $labsLeft", style = MaterialTheme.typography.bodySmall, color = C.warn)
    }
}
