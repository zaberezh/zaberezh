package by.zaberezh.forma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

private val METHOD: String by lazy { Checkup::class.java.getResource("/method.md")?.readText() ?: "" }
private val DM = DateTimeFormatter.ofPattern("d MMM", RU)

@Composable
fun ReportScreen() {
    val ctx = rememberCtx()
    val s = ctx.store
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf(ctx.settings.checkupDays.toString()) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    val n = (days.num()?.toLong() ?: 14L).coerceIn(7L, 120L)
    val to = ctx.today
    val from = to.minusDays(n - 1)
    val secs = remember(ctx, n) { Checkup.sections(ctx, from, to) }
    val text = remember(secs) { Checkup.render(from, to, secs) }
    val facts = remember(secs) { Checkup.facts(ctx, from, to, secs) }

    Screen {
        item {
            Block("Период") {
                Line {
                    Field("Дней", days, { days = it }, Modifier.width(100.dp))
                    Muted("${DM.format(from)} — ${DM.format(to)}" + (Checkup.daysSinceLast(ctx)?.let { "\nпрошлый чекап $it дн. назад" } ?: ""), Modifier.weight(1f))
                }
            }
        }
        items(secs, key = { it.title }) { SectionCard(it) }
        item {
            Block("Анализ") {
                Field("Комментарий: сон, самочувствие, боли…", note, { note = it }, number = false, lines = 2)
                Line {
                    Primary(if (busy) "Анализирую…" else "Анализ Claude", {
                        val st = ctx.settings
                        if (st.apiKey.isBlank()) { err = "API-ключ не задан (Настройки → Claude). Можно скопировать отчёт в чат."; return@Primary }
                        busy = true; err = null
                        scope.launch {
                            val ai = Claude(st.apiKey, st.model, st.apiUrl)
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
                        c.copy("Чекап из моего трекера Forma. Проанализируй по методике, без мотивации: что идёт, что нет, что конкретно поменять.\n\n" +
                            "МЕТОДИКА:\n$METHOD\n\nОТЧЁТ:\n$text\n\nДАННЫЕ:\n$facts\n\nКОММЕНТАРИЙ: ${note.ifBlank { "—" }}")
                        info = "Скопировано"
                    })
                }
                Err(err); Note(info)
            }
        }
        val history = CHECKUP.all(s).reversed()
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
