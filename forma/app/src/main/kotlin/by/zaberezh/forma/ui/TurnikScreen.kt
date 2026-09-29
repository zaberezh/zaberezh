package by.zaberezh.forma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.daily.CounterDef
import by.zaberezh.forma.core.daily.CounterModule
import by.zaberezh.forma.core.r1
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

private val DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)
private val DM = DateTimeFormatter.ofPattern("d MMM", RU)

/** Вкладка «Турник»: ежедневные подходы и еженедельный тест максимума. */
@Composable
fun TurnikScreen() {
    val ctx = rememberCtx()
    Screen {
        items(ctx.settings.counters, key = { "c-" + it.id }) { SetsBlock(ctx, it) }
        items(ctx.settings.counters, key = { "h-" + it.id }) { HistoryBlock(ctx, it) }
        items(ctx.settings.tests, key = { "t-" + it.id }) { TestBlock(ctx, it) }
    }
}

@Composable
private fun SetsBlock(ctx: Ctx, def: CounterDef) {
    val s = ctx.store
    var day by remember { mutableStateOf(ctx.today) }
    val d = CounterModule.day(s, def.id, day)
    var reps by remember(day) { mutableStateOf("") }
    var exact by remember(day) { mutableStateOf<String?>(null) }
    Block(def.title) {
        Line {
            Flat("‹", { day = day.minusDays(1) })
            Text(if (day == ctx.today) "Сегодня" else DAY.format(day), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Flat("›", { if (day < ctx.today) day = day.plusDays(1) }, if (day < ctx.today) C.accent else C.line)
        }
        BigValue("${d.n}", "за день", when {
            d.sets.size > 1 -> d.sets.joinToString(" + ") + " · подходов ${d.sets.size}"
            d.sets.size == 1 -> "1 подход"
            d.n > 0 -> "итог введён числом"
            else -> "не отмечено — считается 0"
        })
        if (d.sets.isNotEmpty()) {
            Muted("Нажми на подход, чтобы удалить его")
            Buttons {
                d.sets.forEachIndexed { i, r ->
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(C.good.copy(alpha = 0.16f))
                        .clickable { CounterModule.removeSet(s, def.id, day, i) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("$r  ×", color = C.good, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Line {
            Field("Подход: сколько раз", reps, { reps = it }, Modifier.weight(1f), suffix = def.unit)
            Primary("Добавить", { reps.num()?.toInt()?.let { CounterModule.addSet(s, def.id, day, it); reps = "" } })
        }
        // быстрые кнопки — частые размеры подходов
        val quick = remember(ctx) {
            by.zaberezh.forma.core.daily.COUNT.all(s).flatMap { it.second.sets }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(5).map { it.key }.sorted()
        }
        if (quick.isNotEmpty()) Buttons {
            quick.forEach { q -> Secondary("+$q", { CounterModule.addSet(s, def.id, day, q) }) }
        }
        if (exact == null) Flat("Ввести итог дня числом (без подходов)", { exact = "" }, C.muted)
        else Line {
            Field("Итог дня", exact!!, { exact = it }, Modifier.weight(1f), suffix = def.unit)
            Secondary("Записать", { exact!!.num()?.toInt()?.let { CounterModule.set(s, def.id, day, it); exact = null } })
        }
    }
}

@Composable
private fun HistoryBlock(ctx: Ctx, def: CounterDef) {
    val s = ctx.store
    val days = CounterModule.series(s, def.id, ctx.today.minusDays(13), ctx.today)
    val week = days.takeLast(7)
    val prevWeek = days.take(7)
    val max = (days.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val best = CounterModule.bestSet(s, def.id)
    Block("Статистика") {
        // столбики за 14 дней
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            days.forEach { (d, v) ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    if (v > 0) Text("$v", style = MaterialTheme.typography.labelSmall, color = C.muted)
                    Box(Modifier.fillMaxWidth().height((70.0 * v / max).dp.coerceAtLeast(2.dp)).clip(RoundedCornerShape(3.dp))
                        .background(if (v > 0) C.accent else C.line)
                        .then(if (d == ctx.today) Modifier.border(1.dp, C.text, RoundedCornerShape(3.dp)) else Modifier))
                    Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, RU), style = MaterialTheme.typography.labelSmall, color = C.muted)
                }
            }
        }
        val w = week.sumOf { it.second }; val pw = prevWeek.sumOf { it.second }
        Stat("За 7 дней", "$w" + if (pw > 0) "  (${if (w >= pw) "+" else ""}${w - pw} к прошлой неделе)" else "")
        Stat("В среднем в день", (w / 7.0).r1())
        Stat("Пропусков за неделю", "${week.count { it.second == 0 }} из 7")
        best?.let { Stat("Лучший подход", "${it.second} (${DM.format(it.first)})", C.good) }
    }
}

