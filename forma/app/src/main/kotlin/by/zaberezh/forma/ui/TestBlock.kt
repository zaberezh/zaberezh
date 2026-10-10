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
    val today = last?.takeIf { it.first == ctx.today }?.second
    // теста сегодня не было, но подходы записаны — максимум взят из лучшего подхода
    val fromSets = today != null && TestModule.recorded(s, def.id).lastOrNull()?.first != ctx.today
    var v by remember(today) { mutableStateOf(today?.let(TestModule::fmt) ?: "") }
    Block(def.title, trailing = {
        when {
            today != null -> Pill(if (fromSets) "из подходов" else "сегодня записано", C.good)
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
        if (results.size > 1) Muted(results.takeLast(8).reversed().joinToString("   ") { "${DM.format(it.first)}: ${TestModule.fmt(it.second)}" })
        Inset {
            Line {
                Field("Результат сегодня", v, { v = it }, Modifier.weight(1f), suffix = def.unit)
                Primary(if (today == null || fromSets) "Записать" else "Изменить", { v.num()?.let { TestModule.record(s, def.id, ctx.today, it) } })
            }
            if (def.hint.isNotEmpty()) Muted(def.hint)
            if (fromSets) Muted("Теста сегодня не было — показан лучший подход из записанных. Сделаешь тест — запиши, он заменит.")
            if (last != null && last.first == ctx.today && !fromSets) Flat("Удалить сегодняшний результат", { TestModule.delete(s, def.id, ctx.today) }, C.muted)
        }
    }
}

