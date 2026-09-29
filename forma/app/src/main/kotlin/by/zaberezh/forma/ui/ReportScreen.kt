package by.zaberezh.forma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import by.zaberezh.forma.core.ai.Claude
import by.zaberezh.forma.core.report.CHECKUP
import by.zaberezh.forma.core.report.Checkup
import by.zaberezh.forma.core.report.CheckupRec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val METHOD: String by lazy { Checkup::class.java.getResource("/method.md")?.readText() ?: "" }

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
    var open by remember { mutableStateOf<String?>(null) }
    val n = (days.num()?.toLong() ?: 14L).coerceIn(7L, 120L)
    val to = ctx.today
    val from = to.minusDays(n - 1)
    val secs = remember(ctx, n) { Checkup.sections(ctx, from, to) }
    val text = remember(secs) { Checkup.render(from, to, secs) }
    val facts = remember(secs) { Checkup.facts(ctx, from, to, secs) }

    Screen {
        item {
            Block("Чекап") {
                Line {
                    Field("дней", days, { days = it }, Modifier.weight(0.4f))
                    Muted("$from — $to" + (Checkup.daysSinceLast(ctx)?.let { "\nпрошлый: $it дн. назад" } ?: ""))
                }
                Text(text)
                Field("Комментарий для анализа (сон, самочувствие, боли…)", note, { note = it }, number = false, lines = 2)
                Line {
                    Button(enabled = !busy, onClick = {
                        val key = ctx.settings.apiKey
                        if (key.isBlank()) { err = "API-ключ Claude не задан (Настройки). Используй «Копировать для чата»."; return@Button }
                        busy = true; err = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Claude(key, ctx.settings.model).analyze(METHOD, text, facts, note) } }
                            busy = false
                            r.onSuccess { CHECKUP.save(s, CheckupRec(from.toString(), to.toString(), text, facts, it)) }
                                .onFailure { err = it.message ?: it.toString() }
                        }
                    }) { Text("Анализ Claude") }
                    OutlinedButton(onClick = { CHECKUP.save(s, CheckupRec(from.toString(), to.toString(), text, facts)) }) { Text("Сохранить") }
                    if (busy) CircularProgressIndicator()
                }
                OutlinedButton(onClick = {
                    c.copy("Чекап из моего трекера Forma. Проанализируй по методике, без мотивации: что идёт, что нет, что конкретно поменять.\n\n" +
                        "МЕТОДИКА:\n$METHOD\n\nОТЧЁТ:\n$text\n\nДАННЫЕ:\n$facts\n\nКОММЕНТАРИЙ: ${note.ifBlank { "—" }}")
                }) { Text("Копировать для чата с Claude") }
                Err(err)
            }
        }
        items(CHECKUP.all(s).reversed(), key = { it.first.id }) { (e, r) ->
            Block("${r.from} — ${r.to}" + if (r.ai != null) " · анализ" else "") {
                if (open == e.id) {
                    r.ai?.let { Text(it) }
                    Muted(r.text)
                    OutlinedButton(onClick = { s.delete(e.id) }) { Text("Удалить") }
                } else Muted((r.ai ?: r.text).take(200) + "…")
                Text(if (open == e.id) "свернуть" else "открыть", Modifier.clickable { open = if (open == e.id) null else e.id })
            }
        }
    }
}
