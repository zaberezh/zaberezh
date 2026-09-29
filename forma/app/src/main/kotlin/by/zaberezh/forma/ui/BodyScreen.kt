package by.zaberezh.forma.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

@Composable
fun BodyScreen() {
    val ctx = rememberCtx()
    val s = ctx.store
    val c = LocalContext.current
    Screen {
        item {
            var w by remember { mutableStateOf("") }
            Block("Вес") {
                Line {
                    Field("кг (утром натощак)", w, { w = it }, Modifier.weight(1f))
                    Button(onClick = { w.num()?.let { WEIGHT.save(s, Weight(it)); w = "" } }) { Text("Сохранить") }
                }
                trendWeight(s, ctx.today)?.let { Text("Сглаженный: ${it.r1()} кг") }
                weightRate(s, ctx.today)?.let { Text("Темп за 3 нед: ${it.r2()} кг/нед (цель ${ctx.settings.profile.gainKgPerWeek.r2()})") }
                Muted(WEIGHT.all(s).takeLast(14).reversed().joinToString("  ") { "${it.first.day.dayOfMonth}: ${it.second.kg.r1()}" })
            }
        }
        item {
            val last = BodyModule.lastMeasure(s)
            val vals = remember(last?.first?.id) { mutableStateMapOf<String, String>().apply { last?.second?.v?.forEach { (k, v) -> put(k, v.r1()) } } }
            Block("Замеры (раз в 4 недели)" + (last?.let { " · прошлые ${it.first.day}" } ?: "")) {
                MEASURE_FIELDS.forEach { f ->
                    Field("${f.label}, ${f.unit}", vals[f.key] ?: "", { vals[f.key] = it })
                    if (f.hint.isNotEmpty()) Muted(f.hint)
                }
                Button(onClick = {
                    val m = vals.mapNotNull { (k, v) -> v.num()?.let { k to it } }.toMap()
                    if (m.isNotEmpty()) MEASURE.save(s, Measure(m))
                }) { Text("Сохранить замеры") }
                last?.second?.v?.let { v ->
                    navyBodyFat(v["waist"] ?: 0.0, v["neck"] ?: 0.0, ctx.settings.profile.heightCm)?.let { Muted("% жира (ВМС, ±3%): ${it.r1()}") }
                }
            }
        }
        item {
            var pending by remember { mutableStateOf<Pair<File, String>?>(null) }
            val shot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
                pending?.let { (f, pose) -> if (ok && f.length() > 0) PHOTO.save(s, Photo(f.absolutePath, pose)) else f.delete() }
                pending = null
            }
            Block("Фото формы (раз в месяц, один свет и расстояние)") {
                Line {
                    POSES.forEach { (id, label) ->
                        OutlinedButton(onClick = {
                            val dir = File(c.filesDir, "photos").apply { mkdirs() }
                            val f = File(dir, "${ctx.today}_${id}_${System.currentTimeMillis()}.jpg")
                            pending = f to id
                            val uri: Uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
                            shot.launch(uri)
                        }) { Text(label) }
                    }
                }
                // сравнение: первое и последнее фото по каждому ракурсу
                val all = PHOTO.all(s).map { it.first to it.second }
                POSES.forEach { (id, label) ->
                    val list = all.filter { it.second.pose == id }
                    if (list.isNotEmpty()) {
                        Muted("$label: ${list.first().first.day} → ${list.last().first.day} (${list.size} шт.)")
                        Line {
                            listOf(list.first(), list.last()).distinctBy { it.first.id }.forEach { (e, p) ->
                                Column(Modifier.weight(1f)) {
                                    Thumb(p.path)
                                    Muted(e.day.toString())
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
    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.height(220.dp), contentScale = ContentScale.Fit)
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
