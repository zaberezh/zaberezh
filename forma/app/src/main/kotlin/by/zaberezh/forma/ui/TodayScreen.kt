package by.zaberezh.forma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.body.weightOn
import by.zaberezh.forma.core.daily.TestModule
import by.zaberezh.forma.core.food.FoodModule
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.Planner
import by.zaberezh.forma.core.i
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.report.Checkup
import by.zaberezh.forma.core.sleep.SleepModule
import by.zaberezh.forma.core.sleep.dur
import by.zaberezh.forma.core.store.ZONE
import by.zaberezh.forma.core.study.Bsuir
import by.zaberezh.forma.core.study.STUDY_PREFS
import by.zaberezh.forma.core.study.Study
import by.zaberezh.forma.core.study.TIMETABLE
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val RU: Locale = Locale.forLanguageTag("ru")
private val DATE = DateTimeFormatter.ofPattern("d MMMM", RU)
private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** Пункт плана дня: [done] — уже сделано, [tab] — куда ведёт нажатие. */
private data class DayTask(val title: String, val sub: String, val done: Boolean, val tab: Int)

/**
 * «Сегодня» — не копия других вкладок, а план дня по обоим разделам: что ещё осталось сделать
 * (каждый пункт отмечается сам, когда сделан в своей вкладке) и пары на сегодня.
 */
@Composable
fun TodayScreen(onTab: (Int) -> Unit, onStudy: (Int) -> Unit) {
    val ctx = rememberCtx()
    val tasks = dayTasks(ctx)
    Screen {
        item {
            Text(
                ctx.today.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() } + ", " + DATE.format(ctx.today),
                style = MaterialTheme.typography.bodyLarge, color = C.muted,
            )
        }
        item {
            val done = tasks.count { it.done }
            Block("План на сегодня", trailing = { Pill("$done из ${tasks.size}", if (done == tasks.size) C.good else C.accent) }) {
                if (done == tasks.size) Note("Всё сделано — можно отдыхать")
                tasks.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = C.line)
                    TaskLine(t) { onTab(t.tab) }
                }
                SleepLine(ctx) { onTab(3) }
            }
        }
        item { StudyToday(ctx, onStudy) }
    }
}

private fun dayTasks(ctx: Ctx): List<DayTask> {
    val s = ctx.store
    val st = ctx.settings
    return buildList {
        if (st.weighReminder) {
            val w = weightOn(s, ctx.today)
            add(DayTask("Взвеситься", w?.let { "${it.r1()} кг" } ?: "натощак, до завтрака", w != null, 5))
        }
        val wk = GymModule.week(ctx)
        val active = s.kvGet(ACTIVE)?.let { s.get(it) } != null
        val trained = ctx.today in GymModule.trainedDays(ctx, ctx.today, ctx.today)
        when {
            trained -> add(DayTask("Тренировка", "засчитана", true, 1))
            active -> add(DayTask("Тренировка идёт", "продолжить и записать подходы", false, 1))
            wk.todayGym -> {
                val plan = GymModule.planFor(ctx, ctx.today)
                val summary = Planner.summary(GymModule.program(s), plan)
                add(DayTask("Зал", summary.ifEmpty { "добавь упражнения в базу" }, false, 1))
            }
            else -> add(DayTask("Отдых от зала", "по плану недели · ${wk.done} из ${st.sessionsPerWeek} сделано", true, 1))
        }
        st.tests.filter { it.everyDays == 1 || TestModule.due(ctx, it) }.forEach { t ->
            val today = TestModule.results(s, t.id).lastOrNull()?.takeIf { it.first == ctx.today }
            add(DayTask(t.title, today?.let { "записано: ${TestModule.fmt(it.second)} ${t.unit}" } ?: "один подход до отказа", today != null, 2))
        }
        val eaten = FoodModule.dayTotal(s, ctx.today)
        val goal = FoodModule.targets(ctx)
        add(DayTask("Белок ${eaten.p.i()} из ${goal.p} г", "калории ${eaten.kcal.i()} из ${goal.kcal}", eaten.p >= goal.p * 0.9, 4))
        if (Checkup.due(ctx)) add(DayTask("Чекап", "пора подвести итоги", false, 6))
    }
}

@Composable
private fun TaskLine(t: DayTask, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    CheckCircle(t.done, onClick, 26.dp)
    Column(Modifier.weight(1f)) {
        Text(t.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (t.done) C.muted else C.text)
        Muted(t.sub)
    }
    Text("›", color = C.muted, style = MaterialTheme.typography.titleMedium)
}

/** Сон — не задача, а подсказка: сколько спал и во сколько ложиться. */
@Composable
private fun SleepLine(ctx: Ctx, onClick: () -> Unit) {
    val night = SleepModule.lastNight(ctx.store, ctx.today)
    val bed = SleepModule.bedtime(ctx).format(HM)
    HorizontalDivider(color = C.line)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Muted((night?.let { "Ночью спал ${dur(it.minutes)} · " } ?: "") + "отбой сегодня в $bed", Modifier.weight(1f))
        Text("›", color = C.muted, style = MaterialTheme.typography.titleMedium)
    }
}

/** Пары на сегодня (текущая выделена), ДЗ к ним и лабы в работе. */
@Composable
private fun StudyToday(ctx: Ctx, onStudy: (Int) -> Unit) {
    val s = ctx.store
    val tt = TIMETABLE.get(s)
    val sub = STUDY_PREFS.get(s).subgroup
    val lessons = Bsuir.on(tt, ctx.today, sub)
    val now = LocalTime.now(ZONE)
    fun time(x: String) = runCatching { LocalTime.parse(x) }.getOrNull()
    Block("Учёба сегодня", trailing = { if (lessons.isNotEmpty()) Pill("${lessons.size} " + plural(lessons.size, "пара", "пары", "пар"), C.study) }) {
        when {
            tt.lessons.isEmpty() -> Muted("Расписание ещё не загружено — открой «Учёба → Расписание».")
            lessons.isEmpty() -> {
                Muted("Сегодня пар нет.")
                (1L..7L).map { ctx.today.plusDays(it) }.firstNotNullOfOrNull { d -> Bsuir.on(tt, d, sub).firstOrNull()?.let { d to it } }?.let { (d, l) ->
                    Muted("Дальше: ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, RU)}, ${l.start} — ${l.title}")
                }
            }
            else -> lessons.forEach { l ->
                val start = time(l.start); val end = time(l.end)
                val past = end != null && now.isAfter(end)
                val current = start != null && end != null && !now.isBefore(start) && !now.isAfter(end)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (current) C.study.copy(alpha = 0.12f) else C.card).clickable { onStudy(0) }.padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(l.start, Modifier.width(44.dp), style = MaterialTheme.typography.bodyMedium, color = if (past) C.muted else C.text)
                    Box(Modifier.size(width = 4.dp, height = 28.dp).clip(RoundedCornerShape(2.dp)).background(typeColor(l.type).copy(alpha = if (past) 0.4f else 1f)))
                    Column(Modifier.weight(1f)) {
                        Text(l.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            color = if (past) C.muted else C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted(listOf(l.type, l.rooms.joinToString(", ")).filter(String::isNotBlank).joinToString(" · "))
                    }
                    if (current) Pill("сейчас", C.study)
                }
            }
        }
        val hw = Study.open(s, ctx.today).count { it.second.due == ctx.today.toString() }
        val all = Study.labs(s).map { it.second }
        val labs = all.count { it.stage == 0 }
        val submit = all.count { it.stage == 1 }
        if (hw > 0 || labs > 0 || submit > 0) Buttons {
            if (hw > 0) Pill("ДЗ на сегодня: $hw", C.warn)
            if (submit > 0) Pill("сдать лаб: $submit", C.warn)
            if (labs > 0) Pill("в работе: $labs", C.study)
            Flat("Лабы ›", { onStudy(1) }, C.study)
        }
    }
}
