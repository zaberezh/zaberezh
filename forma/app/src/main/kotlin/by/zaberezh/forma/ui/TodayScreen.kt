package by.zaberezh.forma.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import by.zaberezh.forma.Forma
import by.zaberezh.forma.core.body.WEIGHT
import by.zaberezh.forma.core.body.Weight
import by.zaberezh.forma.core.body.trendWeight
import by.zaberezh.forma.core.food.FoodModule
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.i
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.report.Checkup
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale.forLanguageTag("ru")

@Composable
fun TodayScreen(onTab: (Int) -> Unit) {
    val ctx = rememberCtx()
    val s = ctx.store
    val wk = GymModule.week(ctx)
    val mon = ctx.today.with(DayOfWeek.MONDAY)
    val trained = GymModule.trainedDays(ctx, mon, mon.plusDays(6))
    Screen {
        item {
            Text("${ctx.today.dayOfWeek.getDisplayName(TextStyle.FULL, RU)}, ${ctx.today}", style = MaterialTheme.typography.titleMedium)
        }
        item {
            Block("Неделя: ${wk.done}/${ctx.settings.sessionsPerWeek}") {
                Text((0L..4L).joinToString("   ") { d ->
                    val day = mon.plusDays(d)
                    val mark = when { day in trained -> "✓"; day in wk.plan -> "●"; else -> "·" }
                    day.dayOfWeek.getDisplayName(TextStyle.SHORT, RU) + " " + mark
                })
                Muted("✓ сделано   ● по плану   · отдых")
                val day = GymModule.nextDay(s)
                when {
                    ctx.today in trained -> Text("Сегодня тренировка засчитана")
                    wk.todayGym -> {
                        Text("Сегодня зал: день ${day.name}" + if (!wk.canSkipToday) " — перенос сорвёт цель недели" else " (можно перенести)")
                        Button(onClick = { onTab(1) }) { Text("К тренировке") }
                    }
                    wk.achievable < ctx.settings.sessionsPerWeek -> Text("Сегодня отдых. Цель недели уже недостижима: максимум ${wk.achievable}")
                    else -> Text("Сегодня отдых")
                }
            }
        }
        item {
            val t = FoodModule.dayTotal(s, ctx.today); val g = FoodModule.targets(ctx)
            Block("Питание сегодня") {
                Text("${t.kcal.i()} / ${g.kcal} ккал")
                Text("Б ${t.p.i()}/${g.p}   Ж ${t.f.i()}/${g.f}   У ${t.c.i()}/${g.c}")
                Button(onClick = { onTab(2) }) { Text("Записать еду") }
            }
        }
        item {
            val todayW = WEIGHT.all(s).lastOrNull()?.takeIf { it.first.day == ctx.today }?.second?.kg
            var w by remember { mutableStateOf("") }
            Block("Вес") {
                Text(todayW?.let { "Сегодня: ${it.r1()} кг" } ?: "Сегодня не взвешивался")
                trendWeight(s, ctx.today)?.let { Muted("Сглаженный: ${it.r1()} кг") }
                Line {
                    Field("кг", w, { w = it }, Modifier.weight(1f))
                    Button(onClick = { w.num()?.let { WEIGHT.save(s, Weight(it)); w = "" } }) { Text("Сохранить") }
                }
            }
        }
        item {
            val lines = Checkup.morning(ctx)
            if (lines.isNotEmpty()) Block("Сводка (как в утреннем уведомлении)") { lines.forEach { Text(it) } }
        }
    }
}
