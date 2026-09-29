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
import by.zaberezh.forma.core.body.PHOTO
import by.zaberezh.forma.core.body.POSES
import by.zaberezh.forma.core.body.Photo
import by.zaberezh.forma.core.body.WEIGHT
import by.zaberezh.forma.core.body.Weight
import by.zaberezh.forma.core.body.navyBodyFat
import by.zaberezh.forma.core.body.trendWeight
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
            var w by remember { mutableStateOf("") }
            val all = WEIGHT.all(s)
            val trend = trendWeight(s, ctx.today)
            val rate = weightRate(s, ctx.today)
            val goal = ctx.settings.profile.gainKgPerWeek
            Block("Вес") {
                if (trend != null) BigValue(trend.r1(), "кг", "сглаженный · последний замер ${all.last().second.kg.r1()} кг")
                else Muted("Взвешивайся утром натощак, минимум 4 раза в неделю")
                if (rate != null) Stat("Темп за 3 недели", "${if (rate >= 0) "+" else ""}${rate.r2()} кг/нед",
                    if (abs(rate - goal) <= 0.15) C.good else C.warn)
                Stat("Цель", "+${goal.r2()} кг/нед")
                Line {
                    Field("Вес сегодня", w, { w = it }, Modifier.weight(1f), suffix = "кг")
                    Primary("Сохранить", { w.num()?.let { WEIGHT.save(s, Weight(it)); w = "" } })
                }
                if (all.isNotEmpty()) Buttons {
                    all.takeLast(10).reversed().forEach { (e, x) -> Pill("${DM.format(e.day)}: ${x.kg.r1()}", C.muted) }
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
        item {
            var pending by remember { mutableStateOf<Pair<File, String>?>(null) }
            val shot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
                pending?.let { (f, pose) -> if (ok && f.length() > 0) PHOTO.save(s, Photo(f.absolutePath, pose)) else f.delete() }
                pending = null
            }
            Block("Фото формы") {
                Muted("Раз в месяц: одно место, свет и расстояние.")
                Line {
                    POSES.forEach { (id, label) ->
                        Secondary(label.replaceFirstChar { it.uppercase() }, {
                            val dir = File(c.filesDir, "photos").apply { mkdirs() }
                            val f = File(dir, "${ctx.today}_${id}_${System.currentTimeMillis()}.jpg")
                            pending = f to id
                            val uri: Uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
                            shot.launch(uri)
                        }, Modifier.weight(1f))
                    }
                }
                val all = PHOTO.all(s)
                POSES.forEach { (id, label) ->
                    val list = all.filter { it.second.pose == id }
                    if (list.isNotEmpty()) {
                        Text("${label.replaceFirstChar { it.uppercase() }} · ${list.size} фото", style = MaterialTheme.typography.bodyMedium)
                        Line {
                            listOf(list.first(), list.last()).distinctBy { it.first.id }.forEach { (e, p) ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Thumb(p.path)
                                    Muted(DM.format(e.day))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(path: String) {
    val bmp = remember(path) { runCatching { loadThumb(path) }.getOrNull() }
    if (bmp != null) Image(
        bmp.asImageBitmap(), null,
        Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Crop,
    )
}

private fun loadThumb(path: String): Bitmap? {
    val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return null
    val deg = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    return if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
}
