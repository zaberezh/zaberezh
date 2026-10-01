package by.zaberezh.forma.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.food.Edostavka
import by.zaberezh.forma.core.store.Store
import by.zaberezh.forma.core.study.Bsuir
import by.zaberezh.forma.core.study.Iis
import by.zaberezh.forma.core.study.Lesson
import by.zaberezh.forma.core.study.STUDY_PREFS
import by.zaberezh.forma.core.study.Study
import by.zaberezh.forma.core.study.TIMETABLE
import by.zaberezh.forma.core.study.Timetable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val DAYF = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)
private val DM = DateTimeFormatter.ofPattern("d MMM", RU)
private val DOW = DateTimeFormatter.ofPattern("EE", RU)
private val MONTH = DateTimeFormatter.ofPattern("LLLL", RU)

/** Цвет типа занятия: лекция — акцент раздела, практика — зелёный, лаба — жёлтый, экзамен/зачёт — красный. */
fun typeColor(t: String): Color = when (t.uppercase()) {
    "ЛК" -> C.accent
    "ПЗ" -> C.good
    "ЛР" -> C.warn
    else -> C.bad
}

private fun monday(d: LocalDate) = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** Загрузка расписания (общая для экрана и настроек). */
suspend fun downloadTimetable(s: Store): Result<Unit> {
    val group = STUDY_PREFS.get(s).group
    return withContext(Dispatchers.IO) { runCatching { Bsuir.download(group, Edostavka::httpGet) } }.map { TIMETABLE.set(s, it) }
}

/** Строка ленты: заголовок дня или пара этого дня. */
private sealed class Row2(val day: LocalDate) {
    class Head(day: LocalDate, val empty: Boolean) : Row2(day)
    class Item(day: LocalDate, val lesson: Lesson, val i: Int) : Row2(day)
}

/**
 * Расписание лентой: дни идут подряд, листаешь вниз — следующие дни. Сверху закреплена неделя:
 * стрелки переключают недели, нажатие на день — прокрутка к нему. Нажатие на пару — подробности и ДЗ.
 */
@Composable
fun ScheduleScreen() {
    val ctx = rememberCtx()
    val s = ctx.store
    val tt = TIMETABLE.get(s)
    val prefs = STUDY_PREFS.get(s)
    val today = ctx.today
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        if (loading) return
        loading = true; err = null
        scope.launch { downloadTimetable(s).onFailure { err = it.message ?: "Ошибка загрузки" }; loading = false }
    }
    LaunchedEffect(prefs.group) {
        // расписание, скачанное прошлой версией приложения (без фото преподавателей), перекачивается сразу
        val old = tt.lessons.isNotEmpty() && tt.format < Bsuir.FORMAT
        if (tt.group != prefs.group || old || System.currentTimeMillis() - tt.fetchedAt > 3 * 24 * 3600_000L) refresh()
    }

    // лента: неделя назад и 10 недель вперёд
    val start = monday(today).minusWeeks(1)
    val days = (0L until 7 * 11).map(start::plusDays)
    val rows = remember(tt, prefs.subgroup, start) {
        days.flatMap { d ->
            val ls = if (tt.lessons.isEmpty()) emptyList() else Bsuir.on(tt, d, prefs.subgroup)
            listOf<Row2>(Row2.Head(d, ls.isEmpty())) + ls.mapIndexed { i, l -> Row2.Item(d, l, i) }
        }
    }
    val dayIndex = remember(rows) { rows.withIndex().filter { it.value is Row2.Head }.associate { it.value.day to it.index } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = dayIndex[today] ?: 0)
    val visible by remember(rows) { derivedStateOf { rows.getOrNull(list.firstVisibleItemIndex)?.day ?: today } }
    fun go(d: LocalDate) { dayIndex[d.coerceIn(days.first(), days.last())]?.let { scope.launch { list.animateScrollToItem(it) } } }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
            WeekHeader(tt, visible, today, prefs.subgroup, onDay = ::go, onWeek = { k -> go(monday(visible).plusWeeks(k)) })
        }
        if (tt.lessons.isEmpty()) Box(Modifier.padding(horizontal = 16.dp)) {
            Block("Расписание группы ${prefs.group}") {
                if (loading) Line { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Muted("Загружаю с iis.bsuir.by…") }
                else Muted("Ещё не загружено. Нужен интернет — дальше работает без него. Группа меняется в настройках.")
                Err(err)
                if (!loading) Primary("Загрузить", { refresh() }, Modifier.fillMaxWidth())
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows.size, key = { i -> rows[i].let { r -> if (r is Row2.Item) "l${r.day}-${r.i}" else "h${r.day}" } }) { i ->
                when (val r = rows[i]) {
                    is Row2.Head -> DayTitle(r.day, today, r.empty, Bsuir.week(tt, r.day))
                    is Row2.Item -> LessonCard(s, tt, r.lesson, r.day, prefs.subgroup)
                }
            }
        }
    }
}

/** Закреплённая неделя: месяц, номер учебной недели, стрелки ‹ › — соседние недели, дни с числом пар. */
@Composable
private fun WeekHeader(tt: Timetable, visible: LocalDate, today: LocalDate, subgroup: Int, onDay: (LocalDate) -> Unit, onWeek: (Long) -> Unit) {
    // смахивание: влево — следующая неделя, вправо — предыдущая; блок слегка едет за пальцем
    val week by rememberUpdatedState(onWeek)
    var drag by remember { mutableFloatStateOf(0f) }
    val shift by animateFloatAsState(drag, label = "swipe")
    Card(
    Modifier.fillMaxWidth().graphicsLayer { translationX = shift * 0.35f }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = { if (drag < -120f) week(1) else if (drag > 120f) week(-1); drag = 0f },
                onDragCancel = { drag = 0f },
            ) { change, dx -> change.consume(); drag += dx }
        },
    shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = C.card),
) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val mon = monday(visible)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArrowButton("‹") { onWeek(-1) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(MONTH.format(mon.plusDays(3)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Muted("${Bsuir.week(tt, mon)}-я учебная неделя" + if (mon == monday(today)) " · текущая" else "")
            }
            ArrowButton("›") { onWeek(1) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (0L..6L).map(mon::plusDays).forEach { d ->
                val n = if (tt.lessons.isEmpty()) 0 else Bsuir.on(tt, d, subgroup).size
                val sel = d == visible
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (sel) C.accent.copy(alpha = 0.18f) else C.cardHi).clickable { onDay(d) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(DOW.format(d), style = MaterialTheme.typography.labelSmall, color = if (d == today) C.accent else C.muted)
                    Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelLarge, fontWeight = if (sel || d == today) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel || d == today) C.accent else C.text)
                    Text(if (n == 0) "–" else "$n", style = MaterialTheme.typography.labelSmall, color = C.muted)
                }
            }
        }
        if (monday(visible) != monday(today) || visible != today) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Flat("К сегодняшнему дню", { onDay(today) })
        }
    }
}

}
@Composable
private fun ArrowButton(t: String, onClick: () -> Unit) = Box(
    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(C.cardHi).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Text(t, style = MaterialTheme.typography.titleLarge, color = C.accent) }

@Composable
private fun DayTitle(d: LocalDate, today: LocalDate, empty: Boolean, week: Int) = Row(
    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically,
) {
    val label = when (d) { today -> "Сегодня"; today.plusDays(1) -> "Завтра"; else -> null }
    Text((label?.let { "$it · " } ?: "") + DAYF.format(d).replaceFirstChar { it.uppercase() },
        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = if (d == today) C.accent else C.text)
    Muted(if (empty) "пар нет" else if (d.dayOfWeek == DayOfWeek.MONDAY) "$week-я неделя" else "")
}

/** Пара: свёрнуто — время, предмет, тип и аудитория; нажатие — подробности, преподаватель с фото и ДЗ. */
@Composable
private fun LessonCard(s: Store, tt: Timetable, l: Lesson, date: LocalDate, subgroup: Int) {
    var open by remember(l.subject, l.start, date) { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable { open = !open }, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (open) C.cardHi else C.card),
    ) {
        Column(Modifier.animateContentSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.width(50.dp)) {
                    Text(l.start, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Muted(l.end)
                }
                Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(typeColor(l.type)))
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(l.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = C.text)
                    Text(
                        listOfNotNull(l.type.takeIf { it.isNotBlank() }, l.rooms.joinToString(", ").takeIf { it.isNotBlank() },
                            if (l.subgroup > 0) "${l.subgroup}-я подгр." else null).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall, color = typeColor(l.type),
                    )
                    if (!open && l.teachers.isNotEmpty()) Muted(l.teachers.joinToString(", "))
                }
            }
            if (open) LessonDetails(s, tt, l, date, subgroup)
        }
    }
}

@Composable
private fun LessonDetails(s: Store, tt: Timetable, l: Lesson, date: LocalDate, subgroup: Int) {
    HorizontalDivider(color = C.line)
    Stat("Занятие", l.typeFull.ifBlank { "—" })
    Stat("Недели", if (l.weeks.isEmpty() || l.weeks.size == 4) "каждую" else l.weeks.joinToString(", "))
    if (l.rooms.isNotEmpty()) Stat("Аудитория", l.rooms.joinToString(", "))
    if (l.subgroup > 0) Stat("Подгруппа", "${l.subgroup}-я")
    if (l.note.isNotBlank()) Muted(l.note)
    l.teachers.forEachIndexed { i, short ->
        val full = l.teachersFull.getOrNull(i)?.takeIf { it.isNotBlank() } ?: short
        Line {
            RemoteImage(Iis.photoUrl(l.photos.getOrNull(i)), 52.dp, full.split(" ").take(2).mapNotNull { it.firstOrNull() }.joinToString(""))
            Column(Modifier.weight(1f)) {
                Text(full, style = MaterialTheme.typography.bodyLarge)
                l.teacherInfo.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { Muted(it) }
            }
        }
    }
    // ДЗ к этой паре и записанное на ней
    Study.dueOn(s, l.subject, date).forEach { (e, hw) ->
        Line {
            CheckCircle(hw.done, { Study.setHomeworkDone(s, e.id, !hw.done) }, 28.dp)
            Column(Modifier.weight(1f)) {
                Text("ДЗ: ${hw.text}", style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (hw.done) TextDecoration.LineThrough else null, color = if (hw.done) C.muted else C.text)
                Muted("задано ${DM.format(LocalDate.parse(hw.from))}")
            }
        }
    }
    Study.writtenOn(s, l.subject, date).forEach { (e, hw) ->
        Line {
            Muted("→ к ${DM.format(LocalDate.parse(hw.due))}: ${hw.text}", Modifier.weight(1f))
            DeleteButton("запись ДЗ") { s.delete(e.id) }
        }
    }
    var text by remember(l.subject, l.start, date) { mutableStateOf("") }
    Field("ДЗ к следующему занятию", text, { text = it }, number = false, lines = 2)
    val next = Bsuir.next(tt, l.subject, l.type, date, subgroup)
    Primary(if (next != null) "Записать — появится ${DM.format(next)}" else "Записать ДЗ",
        { Study.addHomework(s, tt, l, date, text, subgroup); text = "" }, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
}
