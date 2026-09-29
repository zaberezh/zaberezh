package by.zaberezh.forma.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import by.zaberezh.forma.core.Modules
import by.zaberezh.forma.core.body.WEIGHT
import by.zaberezh.forma.core.body.Weight
import by.zaberezh.forma.core.body.trendWeight
import by.zaberezh.forma.core.food.FoodModule
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.i
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.report.Checkup
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val RU: Locale = Locale.forLanguageTag("ru")
private val DATE = DateTimeFormatter.ofPattern("d MMMM", RU)

@Composable
fun TodayScreen(onTab: (Int) -> Unit) {
    val ctx = rememberCtx()
    val s = ctx.store
    val wk = GymModule.week(ctx)
    val mon = ctx.today.with(DayOfWeek.MONDAY)
    val trained = GymModule.trainedDays(ctx, mon, mon.plusDays(6))
    val target = ctx.settings.sessionsPerWeek
    Screen {
        item {
            Text(
                ctx.today.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() } + ", " + DATE.format(ctx.today),
                style = MaterialTheme.typography.bodyLarge, color = C.muted,
            )
        }
        item {
            Block("Зал", trailing = { Pill("${wk.done} / $target", if (wk.done >= target) C.good else C.accent) }) {
                Row(Modifier.fillMaxWidth()) {
                    (0L..4L).forEach { d ->
                        val day = mon.plusDays(d)
                        DayDot(day.dayOfWeek.getDisplayName(TextStyle.SHORT, RU), day in trained, day in wk.plan, day == ctx.today, Modifier.weight(1f))
                    }
                }
                val next = GymModule.nextDay(s)
                when {
                    ctx.today in trained -> Stat("Сегодня", "тренировка засчитана", C.good)
                    wk.todayGym -> {
                        Stat("Сегодня", "день ${next.name}")
                        Muted(if (wk.canSkipToday) "Можно перенести без потери цели недели" else "Перенос сорвёт цель недели")
                        Primary("Открыть тренировку", { onTab(1) }, Modifier.fillMaxWidth())
                    }
                    wk.achievable < target -> Stat("Сегодня", "отдых · максимум за неделю ${wk.achievable}", C.warn)
                    else -> Stat("Сегодня", "отдых")
                }
            }
        }
        item {
            val t = FoodModule.dayTotal(s, ctx.today)
            val g = FoodModule.targets(ctx)
            Block("Питание") {
                Progress("Калории", t.kcal, g.kcal, "ккал")
                Progress("Белок", t.p, g.p, "г")
                Progress("Жиры", t.f, g.f, "г")
                Progress("Углеводы", t.c, g.c, "г")
                Secondary("Добавить еду", { onTab(2) }, Modifier.fillMaxWidth())
            }
        }
        item {
            val todayW = WEIGHT.all(s).lastOrNull()?.takeIf { it.first.day == ctx.today }?.second?.kg
            val trend = trendWeight(s, ctx.today)
            Block("Вес") {
                if (todayW != null) BigValue(todayW.r1(), "кг", trend?.let { "сглаженный ${it.r1()} кг" })
                else {
                    var w by remember { mutableStateOf("") }
                    trend?.let { Muted("Сглаженный ${it.r1()} кг · сегодня не взвешивался") }
                    Line {
                        Field("Вес натощак", w, { w = it }, Modifier.weight(1f), suffix = "кг")
                        Primary("OK", { w.num()?.let { WEIGHT.save(s, Weight(it)); w = "" } })
                    }
                }
            }
        }
        item {
            val lines = Modules.all.filter { it.id != GymModule.id }.flatMap { it.morning(ctx) }
            val due = Checkup.due(ctx)
            if (lines.isNotEmpty() || due) Block("Напоминания") {
                lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                if (due) Secondary("Сделать чекап", { onTab(4) }, Modifier.fillMaxWidth())
            }
        }
    }
}
