package by.zaberezh.forma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.gym.ExProgress
import by.zaberezh.forma.core.gym.GOING
import by.zaberezh.forma.core.SETTINGS
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.LEVELS
import by.zaberezh.forma.core.gym.planPace
import by.zaberezh.forma.core.gym.MUSCLES
import by.zaberezh.forma.core.gym.STUCK
import by.zaberezh.forma.core.gym.StrengthPoint
import by.zaberezh.forma.core.gym.TREND_DAYS
import by.zaberezh.forma.core.gym.progress
import by.zaberezh.forma.core.gym.setText
import by.zaberezh.forma.core.gym.targetText
import by.zaberezh.forma.core.pct
import by.zaberezh.forma.core.r1
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

private val DM = DateTimeFormatter.ofPattern("d MMM", RU)

/** Масштаб графиков. Вывод «растёт / стоит» — всегда по последним 6 неделям, период меняет только то, что видно. */
private val PERIODS = listOf("6 нед" to TREND_DAYS, "3 мес" to 91L, "Полгода" to 182L, "Всё" to null)

/**
 * «Прогресс»: по каждому упражнению — растёт ли сила, было → стало, график по тренировкам и что делать,
 * если не идёт. Сверху — сводка по мышцам и общий совет, когда стоит почти всё.
 */
@Composable
fun ProgressTab(ctx: Ctx) {
    var period by rememberSaveable { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    val from = PERIODS[period].second?.let { ctx.today.minusDays(it - 1) }
    val pr = remember(ctx, period) { GymModule.progress(ctx, from) }
    val stuck = pr.items.filter { it.trend.status in STUCK }.sortedWith(compareBy({ it.trend.status != "падает" }, { it.trend.pctWeek ?: 0.0 }))
    val going = pr.items.filter { it.trend.status in GOING }.sortedByDescending { it.trend.pctWeek ?: 0.0 }
    val few = pr.items.filter { it.trend.status !in GOING + STUCK }.sortedByDescending { it.last }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Block("Прогресс силы") {
            Segmented(PERIODS.map { it.first }, period, { period = it })
            // плановый темп зависит от стажа: новичок прибавляет быстро, с опытом — в разы медленнее
            val level = ctx.settings.gymLevel
            val plan = planPace(level)
            Text("Стаж в зале", style = MaterialTheme.typography.labelLarge, color = C.muted)
            Segmented(LEVELS, level, { SETTINGS.set(ctx.store, SETTINGS.get(ctx.store).copy(gymLevel = it)) })
            Stat("Плановый рост", "+${plan.r1()}% силы в неделю")
            if (pr.items.isEmpty()) {
                Muted("За этот период тренировок нет. Записывай подходы — здесь будет видно, где вес растёт, а где стоит.")
                return@Block
            }
            Buttons {
                listOf("растёт", "медленно", "стоит", "падает").forEach { st ->
                    val n = pr.items.count { it.trend.status == st }
                    if (n > 0) Pill("${statusIcon(st)} $st · $n", statusColor(st))
                }
                if (few.isNotEmpty()) Pill("${statusIcon("")} мало данных · ${few.size}", C.muted)
            }
            if (pr.going.isNotEmpty()) Stat("Идёт", pr.going.joinToString(", ", transform = ::muscle))
            if (pr.stuck.isNotEmpty()) Stat("Не идёт", pr.stuck.joinToString(", ", transform = ::muscle))
            pr.notes.forEach { Callout(it) }
            Muted("Сила — лучший подход тренировки, пересчитанный в разовый максимум: так видно и прибавку веса, и прибавку повторов. " +
                "Вывод — по темпу за последние 6 недель: растёт — не медленнее плана, медленно — хотя бы треть плана " +
                "(+${(plan * 0.3).r1()}%), стоит — около нуля, падает — от −1% в неделю.")
        }
        Group("Не идёт", stuck, null, ctx.today, open) { open = if (open == it) null else it }
        Group("Идёт", going, null, ctx.today, open) { open = if (open == it) null else it }
        Group("Мало данных", few, "Для вывода нужно 3 тренировки упражнения за 6 недель.", ctx.today, open) { open = if (open == it) null else it }
    }
}

private fun muscle(m: String) = MUSCLES[m]?.substringBefore(" (") ?: m

/** Значок статуса — статус никогда не передаётся одним цветом. */
fun statusIcon(status: String) = when (status) {
    "растёт" -> "↑"
    "медленно" -> "↗"
    "стоит" -> "→"
    "падает" -> "↓"
    else -> "·"
}

/** Важное общее замечание: значок предупреждения, текст обычным цветом — читается и длинный. */
@Composable
private fun Callout(text: String) = Inset {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("⚠", color = C.warn, style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = C.text)
    }
}

@Composable
private fun Group(title: String, items: List<ExProgress>, hint: String?, today: LocalDate, open: String?, onToggle: (String) -> Unit) {
    if (items.isEmpty()) return
    Block(title, trailing = { Pill("${items.size}", C.muted) }) {
        if (hint != null) Muted(hint)
        items.forEachIndexed { i, x ->
            if (i > 0) HorizontalDivider(color = C.line)
            ExRow(x, today, open == x.ex.id) { onToggle(x.ex.id) }
        }
    }
}

/** Строка упражнения: статус, было → стало за период, спарклайн; нажатие раскрывает график и подробности. */
@Composable
private fun ExRow(x: ExProgress, today: LocalDate, open: Boolean, onToggle: () -> Unit) {
    val st = x.trend.status
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Line {
            Text(x.ex.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Pill("${statusIcon(st)} ${if (st == "нет данных") "давно не делал" else st}", statusColor(st))
        }
        val a = x.points.first(); val b = x.points.last()
        Line {
            Text(if (x.points.size > 1) "${setText(x.ex, a.best)} → ${setText(x.ex, b.best)}" else setText(x.ex, b.best),
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            x.change?.let { Text("сила ${it.pct()}", style = MaterialTheme.typography.bodyMedium, color = C.muted) }
        }
        if (x.points.size > 1) Sparkline(x.points, Modifier.fillMaxWidth().height(32.dp))
        x.trend.pctWeek?.let { Text(paceText(it, x.plan), style = MaterialTheme.typography.bodySmall, color = C.text) }
        val n = x.points.size
        Muted("последняя ${day(x.last, today)} · $n ${plural(n, "тренировка", "тренировки", "тренировок")} за период" +
            if (st != "нет данных" && ChronoUnit.DAYS.between(x.last, today) >= 21) " · давно не было" else "")
        // главное «почему не идёт» видно сразу, остальное — в подробностях
        if (!open) x.advice.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.text) }
    }
    if (open) Details(x, today)
}

/** Темп против плана: «темп +0.6% в неделю при плане +1% — медленнее плана». */
private fun paceText(pct: Double, plan: Double) = "темп ${pct.pct()} в неделю при плане +${plan.r1()}%" + when {
    pct >= plan - 1e-9 -> " — в плане"
    pct > 0 -> " — медленнее плана"
    else -> ""
}

private fun day(d: LocalDate, today: LocalDate) = when (d) {
    today -> "сегодня"
    today.minusDays(1) -> "вчера"
    else -> DM.format(d)
}

@Composable
private fun Details(x: ExProgress, today: LocalDate) {
    var sel by remember(x.ex.id, x.points.size) { mutableIntStateOf(x.points.lastIndex) }
    val p = x.points[sel.coerceIn(0, x.points.lastIndex)]
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${day(p.date, today)} · ${setText(x.ex, p.best)}" + if (!x.reps) " · сила ≈ ${p.value.r1()} кг" else "",
            style = MaterialTheme.typography.labelLarge, color = C.text)
        StrengthChart(x, sel, { sel = it }, today, Modifier.fillMaxWidth().height(170.dp))
        if (x.planFrom?.let { it.date < x.last } == true) Legend()
        Muted("Нажми или веди пальцем по графику — покажет тренировку." +
            if (banded(x.points, today)) " Светлее — последние 6 недель: по ним вывод и план." else "")
        x.trend.pctWeek?.let { Stat("Темп за 6 недель", "${it.pct()} в неделю") }
        Stat("План", "+${x.plan.r1()}% в неделю")
        x.vsPlan?.let { Stat("Сейчас относительно плана", if (abs(it) < 0.5) "в плане" else "${it.pct()}") }
        Stat("По плану через 4 недели", x.planGoal(4))
        Stat("Следующая тренировка", targetText(x.next))
        if (x.next.note.isNotBlank()) Muted(x.next.note)
        x.advice.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        Text("Тренировки", style = MaterialTheme.typography.labelLarge, color = C.muted)
        x.points.takeLast(8).reversed().forEach { pt ->
            Stat(DM.format(pt.date), pt.sets.joinToString("  ") { if (x.reps) "${it.r}" else "${it.w.r1()}×${it.r}" })
        }
    }
}

/** Легенда графика: две линии — сила и план; подписи обычным цветом, линия рядом — опознавательный знак. */
@Composable
private fun Legend() = Line {
    LegendMark(C.accent, dashed = false); Text("сила", style = MaterialTheme.typography.labelSmall, color = C.muted)
    LegendMark(C.muted, dashed = true); Text("план", style = MaterialTheme.typography.labelSmall, color = C.muted)
}

@Composable
private fun LegendMark(color: Color, dashed: Boolean) = Canvas(Modifier.size(18.dp, 8.dp)) {
    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(),
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
}

/** Шкала: «круглые» деления и не уже 12% от значения — чтобы шум в килограмм не выглядел ни ростом, ни падением. */
private class Scale(values: List<Double>) {
    val step: Double
    val lo: Double
    val hi: Double
    init {
        val a = values.min(); val b = values.max()
        val span = maxOf(b - a, b * 0.12, 1.0)
        val mid = (a + b) / 2
        val raw = span / 3
        val mag = 10.0.pow(floor(log10(raw)))
        step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw - 1e-9 }
        lo = maxOf(0.0, floor((mid - span / 2) / step) * step)
        hi = ceil((mid + span / 2) / step) * step
    }
    fun frac(v: Double) = ((v - lo) / (hi - lo)).toFloat()
}

/** График длиннее 6 недель и в них есть тренировки — окно вывода выделяется фоном. */
private fun banded(pts: List<StrengthPoint>, today: LocalDate): Boolean {
    val six = today.minusDays(TREND_DAYS - 1)
    return pts.first().date < six && pts.last().date >= six
}

/** Положение тренировки по горизонтали — по дате, а не по номеру: перерывы видны как перерывы. */
private fun xFrac(pts: List<StrengthPoint>, d: LocalDate): Float {
    val span = ChronoUnit.DAYS.between(pts.first().date, pts.last().date)
    return if (span <= 0) 0.5f else ChronoUnit.DAYS.between(pts.first().date, d).toFloat() / span
}

/** Спарклайн: линия приглушённая, последняя тренировка — точка акцентом. */
@Composable
private fun Sparkline(pts: List<StrengthPoint>, modifier: Modifier) {
    val lineC = C.muted; val dot = C.accent; val ring = C.card
    val sc = remember(pts) { Scale(pts.map { it.value }) }
    Canvas(modifier) {
        val pad = 6.dp.toPx()
        fun at(pt: StrengthPoint) = Offset(pad + xFrac(pts, pt.date) * (size.width - 2 * pad), size.height - pad - sc.frac(pt.value) * (size.height - 2 * pad))
        val path = Path()
        pts.forEachIndexed { i, pt -> val o = at(pt); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, lineC, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val end = at(pts.last())
        drawCircle(ring, 5.5.dp.toPx(), end)
        drawCircle(dot, 4.dp.toPx(), end)
    }
}

/**
 * График силы по тренировкам: одна шкала, тонкие линии сетки, подписи серым. Нажатие или ведение пальцем
 * выбирает тренировку (вместо наведения мышью); последние 6 недель — светлее, по ним считается вывод.
 */
@Composable
private fun StrengthChart(item: ExProgress, sel: Int, onSelect: (Int) -> Unit, today: LocalDate, modifier: Modifier) {
    val pts = item.points
    val tm = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(color = C.muted)
    val lineC = C.accent; val grid = C.line; val ring = C.card; val band = C.cardHi; val cross = C.muted; val planC = C.muted
    // плановая линия — от первой тренировки окна вывода до последней тренировки (если она в периоде графика)
    val planFrom = item.planFrom?.takeIf { it.date >= pts.first().date && it.date < pts.last().date }
    val sc = remember(item) { Scale(pts.map { it.value } + listOfNotNull(planFrom?.let { item.planned(pts.last().date) })) }
    val left = 40.dp; val right = 10.dp; val top = 18.dp; val bottom = 22.dp
    fun nearest(px: Float, width: Float, l: Float, r: Float): Int {
        val w = width - l - r
        return pts.indices.minBy { abs(l + xFrac(pts, pts[it].date) * w - px) }
    }
    Canvas(modifier
        .pointerInput(pts) { detectTapGestures { o -> onSelect(nearest(o.x, size.width.toFloat(), left.toPx(), right.toPx())) } }
        .pointerInput(pts) {
            detectHorizontalDragGestures { change, _ ->
                change.consume()
                onSelect(nearest(change.position.x, size.width.toFloat(), left.toPx(), right.toPx()))
            }
        }) {
        val l = left.toPx(); val r = right.toPx(); val t = top.toPx(); val b = bottom.toPx()
        val w = size.width - l - r; val h = size.height - t - b
        fun x(d: LocalDate) = l + xFrac(pts, d) * w
        fun y(v: Double) = t + (1 - sc.frac(v)) * h

        // последние 6 недель — фон светлее, если график длиннее
        if (banded(pts, today)) {
            val x0 = x(today.minusDays(TREND_DAYS - 1))
            drawRect(band, Offset(x0, t), Size(l + w - x0, h))
            drawText(tm.measure("6 нед", label), topLeft = Offset(x0 + 4.dp.toPx(), 0f))
        }
        // сетка и подписи шкалы
        var v = sc.lo
        while (v <= sc.hi + 1e-9) {
            val yy = y(v)
            drawLine(grid, Offset(l, yy), Offset(l + w, yy), strokeWidth = 1f)
            val txt = tm.measure(v.r1(), label)
            drawText(txt, topLeft = Offset(l - 6.dp.toPx() - txt.size.width, yy - txt.size.height / 2f))
            v += sc.step
        }
        // даты: первая и последняя тренировки
        val d0 = tm.measure(DM.format(pts.first().date), label)
        val d1 = tm.measure(DM.format(pts.last().date), label)
        val dy = t + h + 6.dp.toPx()
        if (pts.size == 1) drawText(d1, topLeft = Offset(l + w / 2 - d1.size.width / 2f, dy))
        else {
            drawText(d0, topLeft = Offset(l, dy))
            drawText(d1, topLeft = Offset(l + w - d1.size.width, dy))
        }
        // план: пунктир, приглушённый — с подписью на конце
        if (planFrom != null) {
            val end = item.planned(pts.last().date)!!
            val a = Offset(x(planFrom.date), y(planFrom.value)); val e = Offset(x(pts.last().date), y(end))
            drawLine(planC, a, e, strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
        }
        // выбранная тренировка — вертикальная линия
        val sp = pts[sel.coerceIn(0, pts.lastIndex)]
        drawLine(cross, Offset(x(sp.date), t), Offset(x(sp.date), t + h), strokeWidth = 1.dp.toPx())
        // линия силы
        val path = Path()
        pts.forEachIndexed { i, pt -> if (i == 0) path.moveTo(x(pt.date), y(pt.value)) else path.lineTo(x(pt.date), y(pt.value)) }
        drawPath(path, lineC, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // точки — пока их немного; выбранная и последняя — крупнее, с кольцом цвета фона
        if (pts.size <= 24) pts.forEach { pt ->
            val o = Offset(x(pt.date), y(pt.value))
            drawCircle(ring, 4.5.dp.toPx(), o)
            drawCircle(lineC, 3.dp.toPx(), o)
        }
        listOf(pts.last(), sp).distinct().forEach { pt ->
            val o = Offset(x(pt.date), y(pt.value))
            drawCircle(ring, 7.dp.toPx(), o)
            drawCircle(lineC, 5.dp.toPx(), o)
        }
    }
}
