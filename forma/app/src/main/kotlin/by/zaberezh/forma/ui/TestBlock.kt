package by.zaberezh.forma.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.daily.TestDef
import by.zaberezh.forma.core.daily.TestModule
import by.zaberezh.forma.core.r1
import java.time.format.DateTimeFormatter

private val DM = DateTimeFormatter.ofPattern("d MMM", RU)

/** Периодический тест: последний результат, «пора» при наступлении срока, история с изменением. */
@Composable
fun TestBlock(ctx: Ctx, def: TestDef) {
    val s = ctx.store
    val results = TestModule.results(s, def.id)
    val last = results.lastOrNull()
    val due = TestModule.due(ctx, def)
    val today = last?.takeIf { it.first == ctx.today }?.second
    var v by remember(today) { mutableStateOf(today?.let(TestModule::fmt) ?: "") }
    Block(def.title, trailing = {
        when {
            today != null -> Pill("сегодня записано", C.good)
            def.everyDays <= 1 -> Pill("сегодня не записано", C.muted)
            due -> Pill(if (last == null) "первый тест" else "пора", C.warn)
            else -> Pill("через ${def.everyDays - (TestModule.sinceLast(ctx, def.id) ?: 0)} дн.", C.muted)
        }
    }) {
        if (last != null) BigValue(TestModule.fmt(last.second), def.unit, "${DM.format(last.first)}" +
            (results.dropLast(1).lastOrNull()?.let { p ->
                val d = last.second - p.second
                " · ${if (d >= 0) "+" else ""}${TestModule.fmt(d)} к прошлому"
            } ?: ""))
        // результаты — рядом с главным числом, ввод — отдельной областью
        results.maxByOrNull { it.second }?.let { (d, best) -> Stat("Рекорд", "${TestModule.fmt(best)} ${def.unit} · ${DM.format(d)}", C.good) }
        PaceBlock(ctx, def)
        if (results.size > 1) Muted(results.takeLast(8).reversed().joinToString("   ") { "${DM.format(it.first)}: ${TestModule.fmt(it.second)}" })
        Inset {
            Line {
                Field("Результат сегодня", v, { v = it }, Modifier.weight(1f), suffix = def.unit)
                Primary(if (today == null) "Записать" else "Изменить", { v.num()?.let { TestModule.record(s, def.id, ctx.today, it) } })
            }
            if (def.hint.isNotEmpty()) Muted(def.hint)
            if (last != null && last.first == ctx.today) Flat("Удалить сегодняшний результат", { TestModule.delete(s, def.id, ctx.today) }, C.muted)
        }
    }
}


/**
 * Темп роста: лучший результат каждой недели за 6 недель, прирост в неделю и план по стажу (тот же, что в «Зал → Прогресс»).
 * Статус — значком и словом, цвет только дублирует.
 */
@Composable
private fun PaceBlock(ctx: Ctx, def: TestDef) {
    val pace = remember(ctx, def.id) { TestModule.pace(ctx, def.id) }
    if (pace.weeks.size < 2) return
    Line {
        Text("Темп роста", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = C.muted)
        Pill("${statusIcon(pace.status)} ${if (pace.status == "растёт") "в плане" else pace.status}", statusColor(pace.status))
    }
    pace.perWeek?.let { Stat("Прирост", "${if (it >= 0) "+" else ""}${it.r1()} ${def.unit} в неделю") }
    val every = if (pace.plan > 0) Math.round(2 / pace.plan) / 2.0 else null   // «+1 раз за 2,5 нед.» — до полунедели
    Stat("План", "+${pace.plan.r1()} в неделю" + (every?.let { " · +1 за ${it.r1()} нед." } ?: ""))
    pace.goal(4)?.let { Stat("По плану через 4 недели", "${TestModule.fmt(Math.floor(it))} ${def.unit}") }
    Muted("Лучшее по неделям: " + pace.weeks.joinToString(" → ") { TestModule.fmt(it.second) } +
        ". План — по стажу из «Зал → Прогресс».")
}
