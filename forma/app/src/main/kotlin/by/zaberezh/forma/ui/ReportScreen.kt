@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package by.zaberezh.forma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import java.time.temporal.ChronoUnit
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.Section
import by.zaberezh.forma.core.ai.Claude
import by.zaberezh.forma.core.report.CHECKUP
import by.zaberezh.forma.core.report.Checkup
import by.zaberezh.forma.core.report.CheckupRec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DM = DateTimeFormatter.ofPattern("d MMM", RU)

@Composable
fun ReportScreen() {
    val ctx = rememberCtx()
    val s = ctx.store
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    // период — любые даты (по умолчанию последние N дней из настроек); храним как epochDay, чтобы пережить поворот экрана
    var fromDay by rememberSaveable { mutableLongStateOf(ctx.today.minusDays(ctx.settings.checkupDays - 1L).toEpochDay()) }
    var toDay by rememberSaveable { mutableLongStateOf(ctx.today.toEpochDay()) }
    var picking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    val to = LocalDate.ofEpochDay(toDay).coerceAtMost(ctx.today)
    val from = LocalDate.ofEpochDay(fromDay).coerceAtMost(to)
    val n = ChronoUnit.DAYS.between(from, to) + 1
    val secs = remember(ctx, from, to) { Checkup.sections(ctx, from, to) }
    val text = remember(secs) { Checkup.render(from, to, secs) }
    val facts = remember(secs) { Checkup.facts(ctx, from, to, secs) }
    val history = remember(ctx) { CHECKUP.all(s).reversed() }   // новые сверху

    if (picking) RangeDialog(from, to, ctx.today, { picking = false }) { a, b -> fromDay = a.toEpochDay(); toDay = b.toEpochDay() }
    Screen {
        item {
            Block("Период", trailing = { Pill("$n " + plural(n.toInt(), "день", "дня", "дней")) }) {
                // нажми на дату — календарь, выбор «с … по …» без ограничений
                Line {
                    DateChip("с", from, Modifier.weight(1f)) { picking = true }
                    Text("—", color = C.muted)
                    DateChip("по", to, Modifier.weight(1f)) { picking = true }
                }
                val last = history.firstOrNull()?.second?.to?.let(LocalDate::parse)
                Buttons {
                    listOf(7L, 14L, 30L, 90L).forEach { d ->
                        val on = to == ctx.today && n == d
                        FilterChip(selected = on, onClick = { toDay = ctx.today.toEpochDay(); fromDay = ctx.today.minusDays(d - 1).toEpochDay() },
                            label = { Text("$d дн.") })
                    }
                    if (last != null && last < ctx.today) FilterChip(selected = from == last.plusDays(1) && to == ctx.today,
                        onClick = { fromDay = last.plusDays(1).toEpochDay(); toDay = ctx.today.toEpochDay() }, label = { Text("с прошлого чекапа") })
                }
                Checkup.daysSinceLast(ctx)?.let { Muted("Прошлый чекап $it дн. назад") }
            }
        }
        items(secs, key = { it.title }) { SectionCard(it) }
        item {
            Block("Анализ") {
                Field("Комментарий: сон, самочувствие, боли…", note, { note = it }, number = false, lines = 2)
                Line {
                    Primary(if (busy) "Анализирую…" else "Анализ Claude", {
                        val st = ctx.settings
                        val apiKey = by.zaberezh.forma.sys.Secrets.claudeKey(c)
                        if (apiKey.isBlank()) { err = "API-ключ не задан (Настройки → Claude). Можно скопировать отчёт в чат."; return@Primary }
                        busy = true; err = null
                        scope.launch {
                            val ai = Claude(apiKey, st.model, st.apiUrl)
                            val r = withContext(Dispatchers.IO) { runCatching { ai.analyze(METHOD, text, facts, note) } }
                            busy = false
                            r.onSuccess { CHECKUP.save(s, CheckupRec(from.toString(), to.toString(), text, facts, it)); info = "Готово — ниже в истории · ~${ai.used} токенов" }
                                .onFailure { err = Claude.explain(it) }
                        }
                    }, Modifier.weight(1f), enabled = !busy)
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                Buttons {
                    Secondary("Сохранить без анализа", { CHECKUP.save(s, CheckupRec(from.toString(), to.toString(), text, facts)); info = "Сохранено" })
                    Secondary("Копировать для чата", {
                        c.copy("Чекап из моего трекера Grind. Проанализируй по методике, без мотивации: что идёт, что нет, что конкретно поменять.\n\n" +
                            "МЕТОДИКА:\n$METHOD\n\nОТЧЁТ:\n$text\n\nДАННЫЕ:\n$facts\n\nКОММЕНТАРИЙ: ${note.ifBlank { "—" }}")
                        info = "Скопировано"
                    })
                }
                Err(err); Note(info)
            }
        }
        if (history.isNotEmpty()) item { Muted("История чекапов") }
        items(history, key = { it.first.id }) { (e, r) ->
            val title = "${DM.format(LocalDate.parse(r.from))} — ${DM.format(LocalDate.parse(r.to))}"
            Block(title, trailing = { if (r.ai != null) Pill("анализ", C.good) }) {
                if (open == e.id) {
                    r.ai?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Muted(r.text)
                    Line {
                        Flat("Свернуть", { open = null })
                        DeleteButton("чекап") { s.delete(e.id) }
                    }
                } else {
                    Muted((r.ai ?: r.text).take(180) + "…", Modifier.clickable { open = e.id })
                    Flat("Открыть", { open = e.id })
                }
            }
        }
    }
}

/** Плашка даты периода: подпись «с»/«по» и дата; нажатие открывает календарь. */
@Composable
private fun DateChip(label: String, d: LocalDate, modifier: Modifier, onClick: () -> Unit) = Column(
    modifier.clip(RoundedCornerShape(12.dp)).background(C.cardHi).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
) {
    Muted(label)
    Text(DMY.format(d), style = MaterialTheme.typography.titleMedium, color = C.accent)
}

private val DMY = DateTimeFormatter.ofPattern("d MMM yyyy", RU)

/** Календарь выбора периода: первое нажатие — начало, второе — конец; будущие дни недоступны. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDialog(from: LocalDate, to: LocalDate, today: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate, LocalDate) -> Unit) {
    fun ms(d: LocalDate) = d.toEpochDay() * 86_400_000L
    val limit = ms(today)
    val st = rememberDateRangePickerState(
        initialSelectedStartDateMillis = ms(from), initialSelectedEndDateMillis = ms(to),
        selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= limit },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                val a = st.selectedStartDateMillis?.let { LocalDate.ofEpochDay(it / 86_400_000L) }
                val b = st.selectedEndDateMillis?.let { LocalDate.ofEpochDay(it / 86_400_000L) } ?: a
                if (a != null && b != null) onPick(minOf(a, b), maxOf(a, b))
                onDismiss()
            }, enabled = st.selectedStartDateMillis != null) { Text("Готово") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    ) {
        DateRangePicker(state = st, modifier = Modifier.weight(1f), showModeToggle = false,
            title = { Text("Период отчёта", Modifier.padding(start = 24.dp, top = 16.dp), style = MaterialTheme.typography.labelLarge) })
    }
}

@Composable
private fun SectionCard(sec: Section) = Block(sec.title) {
    sec.lines.forEach { l ->
        val i = l.indexOf(": ")
        if (i in 1..40) Stat(l.substring(0, i), l.substring(i + 2)) else Text(l, style = MaterialTheme.typography.bodyMedium)
    }
    if (sec.actions.isNotEmpty()) {
        Text("Что сделать", style = MaterialTheme.typography.titleSmall, color = C.warn)
        sec.actions.forEach { a ->
            Row(Modifier.fillMaxWidth()) {
                Text("→ ", color = C.warn)
                Text(a, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
