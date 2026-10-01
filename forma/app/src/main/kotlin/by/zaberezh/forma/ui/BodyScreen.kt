package by.zaberezh.forma.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import by.zaberezh.forma.core.body.BodyModule
import by.zaberezh.forma.core.body.MEASURE
import by.zaberezh.forma.core.body.MEASURE_FIELDS
import by.zaberezh.forma.core.body.Measure
import by.zaberezh.forma.core.body.WEIGHT
import by.zaberezh.forma.core.body.navyBodyFat
import by.zaberezh.forma.core.body.saveWeight
import by.zaberezh.forma.core.body.trendWeight
import by.zaberezh.forma.core.body.weightDays
import by.zaberezh.forma.core.body.weightOn
import by.zaberezh.forma.core.body.weightRate
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.r2
import java.io.File
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val DM = DateTimeFormatter.ofPattern("d MMM", RU)

@Composable
fun BodyScreen() {
    val ctx = rememberCtx()
    val s = ctx.store
    val c = LocalContext.current
    Screen {
        item {
            val todayW = weightOn(s, ctx.today)
            var w by remember(todayW) { mutableStateOf(todayW?.r1() ?: "") }
            val all = WEIGHT.all(s)
            val trend = trendWeight(s, ctx.today)
            val rate = weightRate(s, ctx.today)
            val goal = ctx.settings.profile.gainKgPerWeek
            val streak = by.zaberezh.forma.core.daily.Streaks.weighIns(s, ctx.today)
            Block("Вес", trailing = { StreakPill(streak) }) {
                StreakNote(streak, "взвешивался")
                if (trend != null) BigValue(trend.r1(), "кг", "сглаженный · последний замер ${all.last().second.kg.r1()} кг")
                else Muted("Взвешивайся утром натощак, минимум 4 раза в неделю")
                if (rate != null) Stat("Темп за 3 недели", "${if (rate >= 0) "+" else ""}${rate.r2()} кг/нед",
                    if (abs(rate - goal) <= 0.15) C.good else C.warn)
                Stat("Цель", "+${goal.r2()} кг/нед")
                Line {
                    Field("Вес сегодня", w, { w = it }, Modifier.weight(1f), suffix = "кг")
                    Primary(if (todayW == null) "Сохранить" else "Изменить", { w.num()?.let { saveWeight(s, ctx.today, it) } })
                }
                if (todayW != null) Muted("Сегодня уже записано ${todayW.r1()} кг — новое значение заменит его.")
                val days = weightDays(s, 10)
                if (days.isNotEmpty()) Buttons {
                    days.forEach { (d, kg) -> Pill("${DM.format(d)}: ${kg.r1()}", C.muted) }
                }
            }
        }
        item {
            val last = BodyModule.lastMeasure(s)
            val vals = remember(last?.first?.id) {
                mutableStateMapOf<String, String>().apply { last?.second?.v?.forEach { (k, v) -> put(k, v.r1()) } }
            }
            var saved by remember { mutableStateOf<String?>(null) }
            Block("Замеры", trailing = { last?.let { Pill("прошлые ${DM.format(it.first.day)}", C.muted) } }) {
                Muted("Раз в 4 недели, утром до еды и тренировки, одной и той же лентой. Лента плотно, но не впивается; меряй 2 раза и бери среднее.")
                MEASURE_FIELDS.forEach { f ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Field(f.label, vals[f.key] ?: "", { vals[f.key] = it }, suffix = "см")
                        Muted(f.hint)
                    }
                }
                last?.second?.v?.let { v ->
                    navyBodyFat(v["waist"] ?: 0.0, v["neck"] ?: 0.0, ctx.settings.profile.heightCm)
                        ?.let { Stat("% жира (формула ВМС, ±3%)", it.r1()) }
                }
                Primary("Сохранить замеры", {
                    val m = vals.mapNotNull { (k, v) -> v.num()?.let { k to it } }.toMap()
                    if (m.isNotEmpty()) { MEASURE.save(s, Measure(m)); saved = "Сохранено" }
                }, Modifier.fillMaxWidth())
                Note(saved)
            }
        }
    }
}
