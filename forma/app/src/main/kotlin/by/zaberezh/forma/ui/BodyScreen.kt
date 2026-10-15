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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
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
import by.zaberezh.forma.core.body.withGoal
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
            val streak = by.zaberezh.forma.core.daily.Streaks.weighIns(s, ctx.today)
            Block("Вес", trailing = { StreakPill(streak) }) {
                StreakNote(streak, "взвешивался")
                if (trend != null) BigValue(trend.r1(), "кг", "сглаженный · последний замер ${all.last().second.kg.r1()} кг")
                else Muted("Взвешивайся утром натощак, минимум 4 раза в неделю")
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
        item { WeightGoalBlock(ctx) }
        item {
            // поля — всегда для нового ввода; сохранённое смотрится по дате ниже
            val vals = remember { mutableStateMapOf<String, String>() }
            var saved by remember { mutableStateOf<String?>(null) }
            val history = remember(ctx) { MEASURE.all(s).reversed() }   // новые сверху
            var open by remember { mutableStateOf<String?>(null) }
            Block("Замеры", trailing = { history.firstOrNull()?.let { Pill("прошлые ${DM.format(it.first.day)}", C.muted) } }) {
                Muted("Раз в 4 недели, утром до еды и тренировки, одной и той же лентой. Лента плотно, но не впивается; меряй 2 раза и бери среднее.")
                MEASURE_FIELDS.forEach { f ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Field(f.label, vals[f.key] ?: "", { vals[f.key] = it; saved = null }, suffix = "см")
                        Muted(f.hint)
                    }
                }
                Primary("Сохранить замеры", {
                    val m = vals.mapNotNull { (k, v) -> v.num()?.takeIf { it > 0 }?.let { k to it } }.toMap()
                    if (m.isNotEmpty()) {
                        val e = MEASURE.save(s, Measure(m))
                        vals.clear(); open = e.id
                        saved = "Сохранено — замеры за ${DM.format(e.day)} ниже"
                    } else saved = null
                }, Modifier.fillMaxWidth(), enabled = vals.values.any { it.num() != null })
                Note(saved)
                if (history.isNotEmpty()) {
                    Muted("Сохранённые замеры — нажми на дату:")
                    Buttons {
                        history.forEach { (e, _) ->
                            androidx.compose.material3.FilterChip(selected = open == e.id, onClick = { open = if (open == e.id) null else e.id },
                                label = { Text(DM.format(e.day)) })
                        }
                    }
                }
                history.withIndex().firstOrNull { it.value.first.id == open }?.let { (i, cur) ->
                    val (e, m) = cur
                    val prev = history.getOrNull(i + 1)?.second?.v
                    androidx.compose.material3.HorizontalDivider(color = C.line)
                    Text("Замеры за " + java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", RU).format(e.day),
                        style = MaterialTheme.typography.titleSmall)
                    MEASURE_FIELDS.forEach { f ->
                        m.v[f.key]?.let { v ->
                            val d = prev?.get(f.key)?.let { v - it }
                            Stat(f.label, "${v.r1()} см" + (d?.takeIf { abs(it) >= 0.05 }?.let { " (${if (it > 0) "+" else ""}${it.r1()})" } ?: ""))
                        }
                    }
                    navyBodyFat(m.v["waist"] ?: 0.0, m.v["neck"] ?: 0.0, ctx.settings.profile.heightCm)
                        ?.let { Stat("% жира (формула ВМС, ±3%)", it.r1()) }
                    if (prev != null) Muted("В скобках — изменение с прошлых замеров")
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        DeleteButton("замеры за ${DM.format(e.day)}") { s.delete(e.id); open = null }
                    }
                }
            }
        }
    }
}

/** Цель по весу: старт → цель, сколько пройдено, когда дойдёшь и идёшь ли по плану. Темп руками не задаётся. */
@Composable
private fun WeightGoalBlock(ctx: by.zaberezh.forma.core.Ctx) {
    val s = ctx.store
    val p = ctx.settings.profile
    val current = trendWeight(s, ctx.today) ?: by.zaberezh.forma.core.body.latestWeight(s)
    val wg = by.zaberezh.forma.core.body.weightGoal(p, current)
    var start by remember(p) { mutableStateOf(p.startKg?.r1() ?: "") }
    var target by remember(p) { mutableStateOf(p.targetKg?.r1() ?: "") }
    var msg by remember(p) { mutableStateOf<String?>(null) }
    Block("Цель по весу", trailing = { if (wg.targetKg != null) Pill(wg.kind, C.muted) }) {
        val t = wg.targetKg
        if (t == null) Muted("Цели нет — калории на поддержание веса. Укажи целевой вес: больше текущего — набор, меньше — похудение.")
        else {
            Stat("Старт → цель", "${wg.startKg?.r1() ?: "—"} → ${t.r1()} кг")
            val done = wg.doneKg; val tot = wg.totalKg; val f = wg.progress
            if (done != null && tot != null && f != null) {
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.line)) {
                    Box(Modifier.fillMaxWidth(f.toFloat()).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (f >= 1.0) C.good else C.accent))
                }
                Muted("Пройдено ${done.coerceAtLeast(0.0).r1()} из ${tot.r1()} кг (${(f * 100).toInt()}%)" +
                    (current?.let { " · сейчас ${it.r1()} кг" } ?: ""))
            }
            val left = current?.let { t - it }
            when {
                current == null -> Muted("Взвесься — тогда покажу прогресс и дату.")
                wg.rate == 0.0 -> Note("Ты у цели — держим вес.")
                else -> {
                    Stat("Осталось", "${abs(left ?: 0.0).r1()} кг")
                    wg.weeks?.let { w -> Stat("Дойдёшь примерно", "к ${DM.format(ctx.today.plusWeeks(w.toLong()))} (≈$w нед)") }
                }
            }
            val v = wg.vsPlan(ctx.today)
            val planned = wg.planned(ctx.today)
            if (v != null && planned != null && wg.startDay?.isBefore(ctx.today.minusDays(6)) == true)
                Stat("По плану сейчас ${planned.r1()} кг", by.zaberezh.forma.core.body.planNote(v), if (abs(v) < 0.7) C.good else C.warn)
        }
        Line {
            Field("Старт", start, { start = it; msg = null }, Modifier.weight(1f), suffix = "кг")
            Field("Цель", target, { target = it; msg = null }, Modifier.weight(1f), suffix = "кг")
        }
        Muted("Старт — с какого веса идёшь к цели; пусто — возьму текущий. Набор идёт по +${by.zaberezh.forma.core.body.GAIN_PACE} кг " +
            "в неделю (мышцы, а не жир), похудение — по −${by.zaberezh.forma.core.body.LOSS_PACE}; калории считаются от этого сами.")
        Line {
            Primary("Сохранить цель", {
                val tv = target.num()?.takeIf { it in 30.0..250.0 }
                val sv = start.num()?.takeIf { it in 30.0..250.0 }
                if (tv == null) { msg = "Цель — число от 30 до 250 кг"; return@Primary }
                val st = by.zaberezh.forma.core.SETTINGS.get(s)
                by.zaberezh.forma.core.SETTINGS.set(s, st.copy(profile = st.profile.withGoal(tv, sv, current, ctx.today)))
                msg = "Цель сохранена"
            }, Modifier.weight(1f))
            if (p.targetKg != null) Secondary("Убрать", {
                val st = by.zaberezh.forma.core.SETTINGS.get(s)
                by.zaberezh.forma.core.SETTINGS.set(s, st.copy(profile = st.profile.withGoal(null, null, current, ctx.today)))
            })
        }
        Note(msg)
    }
}
