package by.zaberezh.forma.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.daily.TestDef
import by.zaberezh.forma.core.daily.TestModule
import java.time.format.DateTimeFormatter

private val DM = DateTimeFormatter.ofPattern("d MMM", RU)

/** Периодический тест: последний результат, «пора» при наступлении срока, история с изменением. */
@Composable
fun TestBlock(ctx: Ctx, def: TestDef) {
    val s = ctx.store
    val results = TestModule.results(s, def.id)
    val last = results.lastOrNull()
    val due = TestModule.due(ctx, def)
    var v by remember { mutableStateOf("") }
    Block(def.title, trailing = {
        if (due) Pill(if (last == null) "первый тест" else "пора", C.warn)
        else Pill("через ${def.everyDays - (TestModule.sinceLast(ctx, def.id) ?: 0)} дн.", C.muted)
    }) {
        if (last != null) BigValue(TestModule.fmt(last.second), def.unit, "${DM.format(last.first)}" +
            (results.dropLast(1).lastOrNull()?.let { p ->
                val d = last.second - p.second
                " · ${if (d >= 0) "+" else ""}${TestModule.fmt(d)} к прошлому"
            } ?: ""))
        if (def.hint.isNotEmpty()) Muted(def.hint)
        Line {
            Field("Результат сегодня", v, { v = it }, Modifier.weight(1f), suffix = def.unit)
            Primary("Записать", { v.num()?.let { TestModule.record(s, def.id, ctx.today, it); v = "" } })
        }
        if (results.size > 1) Muted(results.takeLast(8).reversed().joinToString("   ") { "${DM.format(it.first)}: ${TestModule.fmt(it.second)}" })
        if (last != null && last.first == ctx.today) Flat("Удалить сегодняшний результат", { TestModule.delete(s, def.id, ctx.today) }, C.muted)
    }
}

