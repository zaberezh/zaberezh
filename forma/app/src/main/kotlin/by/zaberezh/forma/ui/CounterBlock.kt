package by.zaberezh.forma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.daily.CounterDef
import by.zaberezh.forma.core.daily.CounterModule
import by.zaberezh.forma.core.r1
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

private val DM = DateTimeFormatter.ofPattern("d MMM", RU)

/** Ежедневный счётчик: сегодня по умолчанию, любой из 7 последних дней можно выбрать и исправить. */
@Composable
fun CounterBlock(ctx: Ctx, def: CounterDef) {
    val s = ctx.store
    var day by remember { mutableStateOf(ctx.today) }
    val n = CounterModule.get(s, def.id, day)
    val week = CounterModule.series(s, def.id, ctx.today.minusDays(6), ctx.today)
    val total = week.sumOf { it.second }
    Block(def.title, trailing = { Pill("за 7 дней: $total", C.muted) }) {
        // последние 7 дней: нажми, чтобы редактировать
        Row(Modifier.fillMaxWidth()) {
            week.forEach { (d, v) ->
                val sel = d == day
                Column(Modifier.weight(1f).clickable { day = d }, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, RU), style = MaterialTheme.typography.labelMedium,
                        color = if (d == ctx.today) C.accent else C.muted)
                    Box(Modifier.height(32.dp).fillMaxWidth(0.85f).clip(RoundedCornerShape(8.dp))
                        .background(if (v > 0) C.good.copy(alpha = 0.2f) else C.cardHi)
                        .then(if (sel) Modifier.border(2.dp, C.accent, RoundedCornerShape(8.dp)) else Modifier),
                        contentAlignment = Alignment.Center) {
                        Text("$v", color = if (v > 0) C.good else C.muted, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Muted(if (day == ctx.today) "Сегодня · нажми на день выше, чтобы исправить прошлый" else "Правка: ${DM.format(day)}")
        Line {
            Text("$n", Modifier.width(72.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Secondary("−1", { CounterModule.set(s, def.id, day, n - 1) }, Modifier.weight(1f))
            Primary("+1", { CounterModule.set(s, def.id, day, n + 1) }, Modifier.weight(1f))
            Primary("+5", { CounterModule.set(s, def.id, day, n + 5) }, Modifier.weight(1f))
        }
        var exact by remember(day) { mutableStateOf("") }
        Line {
            Field("Точное число", exact, { exact = it }, Modifier.weight(1f), suffix = def.unit)
            Secondary("Записать", { exact.num()?.let { CounterModule.set(s, def.id, day, it.toInt()); exact = "" } })
        }
        val avg = total / 7.0
        val best = week.maxOf { it.second }
        Muted("В среднем ${avg.r1()}/день · лучший день $best · пропусков ${week.count { it.second == 0 }} из 7")
    }
}
