package by.zaberezh.forma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.gym.GymModule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val MONTHS = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

/**
 * «Твои годы в зале»: по квадрату на день, светится — день тренировки. Столбец — неделя (пн…вс сверху вниз),
 * по году на строку, от первой тренировки до текущего года.
 */
@Composable
fun GymYears(ctx: Ctx) {
    val today = ctx.today
    val (trained, firstYear) = remember(ctx) {
        val all = GymModule.workouts(ctx.store).filter { it.second.sets.isNotEmpty() }.map { it.first.day }.toSet()
        all to (all.minOrNull()?.year ?: today.year)
    }
    Block("Твои годы в зале") {
        Muted("Квадрат — день, светится — день, когда была тренировка.")
        for (year in firstYear..today.year) {
            val n = trained.count { it.year == year }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$year", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Muted("$n " + plural(n, "тренировка", "тренировки", "тренировок"))
            }
            YearGrid(year, trained, today)
        }
        Text("Увидимся в ${today.year + 1}", Modifier.fillMaxWidth(), color = C.good, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun YearGrid(year: Int, trained: Set<LocalDate>, today: LocalDate) {
    val jan1 = LocalDate.of(year, 1, 1)
    val start = jan1.with(DayOfWeek.MONDAY).let { if (it.isAfter(jan1)) it.minusWeeks(1) else it }
    val end = LocalDate.of(year, 12, 31)
    val weeks = (ChronoUnit.WEEKS.between(start, end) + 1).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // подписи месяцев — над неделей, где месяц начинается
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                var prevWeek = 0
                MONTHS.forEachIndexed { m, name ->
                    val w = (ChronoUnit.DAYS.between(start, LocalDate.of(year, m + 1, 1)) / 7).toInt()
                    val next = if (m == 11) weeks else (ChronoUnit.DAYS.between(start, LocalDate.of(year, m + 2, 1)) / 7).toInt()
                    if (w > prevWeek) Box(Modifier.weight((w - prevWeek).toFloat()))
                    Text(name, Modifier.weight((next - w).coerceAtLeast(1).toFloat()), fontSize = 9.sp, color = C.muted, maxLines = 1)
                    prevWeek = next
                }
            }
        }
        val lit = C.good; val empty = C.cardHi; val future = C.card
        Canvas(Modifier.fillMaxWidth().aspectRatio(weeks / 7f)) {
            val cell = size.width / weeks
            val gap = cell * 0.18f
            for (w in 0 until weeks) for (d in 0 until 7) {
                val day = start.plusDays((w * 7 + d).toLong())
                if (day.year != year) continue
                val color = when {
                    day in trained -> lit
                    day.isAfter(today) -> future
                    else -> empty
                }
                drawRoundRect(color, Offset(w * cell + gap / 2, d * cell + gap / 2), Size(cell - gap, cell - gap), CornerRadius(cell * 0.18f))
            }
        }
    }
}
