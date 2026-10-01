package by.zaberezh.forma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.food.Edostavka
import by.zaberezh.forma.core.store.Store
import by.zaberezh.forma.core.study.Bsuir
import by.zaberezh.forma.core.study.Lesson
import by.zaberezh.forma.core.study.STUDY_PREFS
import by.zaberezh.forma.core.study.Study
import by.zaberezh.forma.core.study.TIMETABLE
import by.zaberezh.forma.core.study.Timetable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val DAYF = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)
private val DM = DateTimeFormatter.ofPattern("d MMM", RU)
private val DOW = DateTimeFormatter.ofPattern("EE", RU)
private val STAMP = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)

/** Цвет типа занятия: лекция — акцент раздела, практика — зелёный, лаба — жёлтый, экзамен/зачёт — красный. */
private fun typeColor(t: String): Color = when (t.uppercase()) {
    "ЛК" -> C.accent
    "ПЗ" -> C.good
    "ЛР" -> C.warn
    else -> C.bad
}

/** Расписание группы БГУИР на день + ДЗ: записанное на паре появляется на следующем занятии по предмету. */
@Composable
fun ScheduleScreen() {
    val ctx = rememberCtx()
    val s = ctx.store
    val tt = TIMETABLE.get(s)
    val prefs = STUDY_PREFS.get(s)
    var date by remember { mutableStateOf(ctx.today) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (loading) return
        loading = true; err = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Bsuir.download(prefs.group, Edostavka::httpGet) } }
            loading = false
            r.onSuccess { TIMETABLE.set(s, it) }.onFailure { err = it.message ?: "Ошибка загрузки" }
        }
    }
    // первое открытие, смена группы или расписанию больше 3 дней — обновить
    LaunchedEffect(prefs.group) {
        if (tt.group != prefs.group || System.currentTimeMillis() - tt.fetchedAt > 3 * 24 * 3600_000L) refresh()
    }

    Screen {
        if (tt.lessons.isEmpty()) item {
            Block("Расписание группы ${prefs.group}") {
                if (loading) Line { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Muted("Загружаю с iis.bsuir.by…") }
                else Muted("Ещё не загружено. Нужен интернет — дальше работает без него.")
                Err(err)
                if (!loading) Primary("Загрузить", { refresh() }, Modifier.fillMaxWidth())
            }
        }
        item { DayHeader(tt, date, ctx.today, prefs.subgroup) { date = it } }

        val lessons = Bsuir.on(tt, date, prefs.subgroup)
        if (tt.lessons.isNotEmpty() && lessons.isEmpty()) item {
            Block { Muted(if (date.dayOfWeek == DayOfWeek.SUNDAY) "Воскресенье — пар нет." else "Пар нет.") }
        }
        lessons.forEach { l -> item { LessonCard(s, tt, l, date, prefs.subgroup) } }

        val open = Study.open(s, ctx.today)
        if (open.isNotEmpty()) item {
            Block("Домашка", trailing = { Pill("${open.size}") }) {
                open.forEachIndexed { i, (e, hw) ->
                    if (i > 0) HorizontalDivider(color = C.line)
                    Line {
                        CheckCircle(hw.done, { Study.setHomeworkDone(s, e.id, !hw.done) })
                        Column(Modifier.weight(1f).clickable { date = LocalDate.parse(hw.due) }) {
                            Text(hw.text, style = MaterialTheme.typography.bodyMedium)
                            Muted("${hw.subject}${if (hw.type.isNotBlank()) " · ${hw.type}" else ""} · к ${DM.format(LocalDate.parse(hw.due))}")
                        }
                    }
                }
            }
        }

        item {
            var group by remember(prefs.group) { mutableStateOf(prefs.group) }
            Block("Группа") {
                Line {
                    Field("Номер группы", group, { group = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f))
                    Secondary("Сохранить", { STUDY_PREFS.set(s, prefs.copy(group = group)) }, enabled = group.length == 6 && group != prefs.group)
                }
                Text("Подгруппа", style = MaterialTheme.typography.labelLarge, color = C.muted)
                Buttons {
                    listOf(0 to "Обе", 1 to "1-я", 2 to "2-я").forEach { (n, label) ->
                        FilterChip(selected = prefs.subgroup == n, onClick = { STUDY_PREFS.set(s, prefs.copy(subgroup = n)) }, label = { Text(label) })
                    }
                }
                Line {
                    Muted(if (tt.fetchedAt > 0) "Обновлено ${STAMP.format(Instant.ofEpochMilli(tt.fetchedAt).atZone(java.time.ZoneId.systemDefault()))}" else "Не загружено",
                        Modifier.weight(1f))
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Flat("Обновить", { refresh() })
                }
                if (tt.lessons.isNotEmpty()) Err(err)
                Muted("Источник — открытое расписание ИИС БГУИР (iis.bsuir.by).")
            }
        }
    }
}

/** Заголовок: дата со стрелками, номер учебной недели и полоска дней недели с числом пар. */
@Composable
private fun DayHeader(tt: Timetable, date: LocalDate, today: LocalDate, subgroup: Int, onDate: (LocalDate) -> Unit) = Block {
    Line {
        Flat("‹", { onDate(date.minusDays(1)) })
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (date) { today -> "Сегодня"; today.plusDays(1) -> "Завтра"; today.minusDays(1) -> "Вчера"; else -> DAYF.format(date).replaceFirstChar { it.uppercase() } },
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            Muted("${Bsuir.week(tt, date)}-я учебная неделя" + if (date == today || date == today.plusDays(1) || date == today.minusDays(1)) " · ${DM.format(date)}" else "")
        }
        Flat("›", { onDate(date.plusDays(1)) })
    }
    val mon = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (0L..6L).map(mon::plusDays).forEach { d ->
            val n = if (tt.lessons.isEmpty()) 0 else Bsuir.on(tt, d, subgroup).size
            val sel = d == date
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) C.accent.copy(alpha = 0.18f) else C.cardHi).clickable { onDate(d) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(DOW.format(d), style = MaterialTheme.typography.labelSmall, color = if (d == today) C.accent else C.muted)
                Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelLarge, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) C.accent else C.text)
                Text(if (n == 0) "–" else "$n", style = MaterialTheme.typography.labelSmall, color = C.muted)
            }
        }
    }
    if (date != today) Flat("К сегодняшнему дню", { onDate(today) })
}

/** Пара: время, тип, аудитория, преподаватель; ДЗ к ней и запись ДЗ на следующее занятие. */
@Composable
private fun LessonCard(s: Store, tt: Timetable, l: Lesson, date: LocalDate, subgroup: Int) = Block {
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Column(Modifier.width(52.dp)) {
            Text(l.start, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Muted(l.end)
        }
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(typeColor(l.type)))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(l.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Buttons {
                if (l.type.isNotBlank()) Pill(l.type, typeColor(l.type))
                if (l.subgroup > 0) Pill("${l.subgroup}-я подгр.", C.muted)
                l.rooms.forEach { Pill(it, C.muted) }
            }
            if (l.teachers.isNotEmpty()) Muted(l.teachers.joinToString(", "))
            if (l.note.isNotBlank()) Muted(l.note)
        }
    }
    // ДЗ, которое надо было сделать к этой паре
    Study.dueOn(s, l.subject, date).forEach { (e, hw) ->
        Line {
            CheckCircle(hw.done, { Study.setHomeworkDone(s, e.id, !hw.done) }, 22.dp)
            Column(Modifier.weight(1f)) {
                Text("ДЗ: ${hw.text}", style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (hw.done) TextDecoration.LineThrough else null, color = if (hw.done) C.muted else C.text)
                Muted("задано ${DM.format(LocalDate.parse(hw.from))}")
            }
        }
    }
    // записанное на этой паре — к следующему занятию
    Study.writtenOn(s, l.subject, date).forEach { (e, hw) ->
        Line {
            Muted("→ ${DM.format(LocalDate.parse(hw.due))}: ${hw.text}", Modifier.weight(1f))
            DeleteButton("запись ДЗ") { s.delete(e.id) }
        }
    }
    var adding by remember(l.subject, l.start, date) { mutableStateOf(false) }
    var text by remember(l.subject, l.start, date) { mutableStateOf("") }
    if (!adding) Flat("+ ДЗ к следующему занятию", { adding = true })
    else {
        Field("Что задали", text, { text = it }, number = false, lines = 2)
        Line {
            Primary("Записать", { if (text.isNotBlank()) { Study.addHomework(s, tt, l, date, text, subgroup); text = ""; adding = false } },
                Modifier.weight(1f), enabled = text.isNotBlank())
            Flat("Отмена", { adding = false; text = "" }, C.muted)
        }
        Bsuir.next(tt, l.subject, l.type, date, subgroup)?.let { Muted("Появится ${DM.format(it)} — на следующем занятии «${l.subject}».") }
    }
}
