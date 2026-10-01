package by.zaberezh.forma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.ai.Claude
import by.zaberezh.forma.core.food.FoodItem
import by.zaberezh.forma.core.food.FoodModule
import by.zaberezh.forma.core.food.LibraryResolver
import by.zaberezh.forma.core.food.MEAL
import by.zaberezh.forma.core.food.Macro
import by.zaberezh.forma.core.food.Meal
import by.zaberezh.forma.core.i
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.store.ZONE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

private fun Macro.line() = "${kcal.i()} ккал · Б ${p.i()} · Ж ${f.i()} · У ${c.i()}"

@Composable
fun FoodScreen() {
    val ctx = rememberCtx()
    val s = ctx.store
    var date by remember { mutableStateOf(ctx.today) }
    var text by FoodSearch.text
    var draft by FoodSearch.draft
    val busy by FoodSearch.busy
    var err by FoodSearch.err
    val info by FoodSearch.info
    val c = LocalContext.current
    DisposableEffect(Unit) { FoodSearch.visible = true; onDispose { FoodSearch.visible = false } }
    var shotUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<android.net.Uri?>(null) }   // переживает возврат из камеры
    fun sendPhoto(uri: android.net.Uri) { photoJpeg(c, uri)?.let { FoodSearch.photo(ctx, c, it) } ?: run { FoodSearch.err.value = "Не удалось прочитать фото" } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) shotUri?.let(::sendPhoto)
        runCatching { java.io.File(c.cacheDir, "photos/meal.jpg").delete() }   // снимок уже сжат в память — на диске не храним
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::sendPhoto) }

    Screen {
        item {
            val t = FoodModule.dayTotal(s, date)
            val g = FoodModule.targets(ctx)
            Block {
                DateNav(date, ctx.today) { date = it }
                Progress("Калории", t.kcal, g.kcal, "ккал")
                Progress("Белок", t.p, g.p, "г")
                Progress("Жиры", t.f, g.f, "г")
                Progress("Углеводы", t.c, g.c, "г")
                Muted("Норма ${g.kcal} ккал = расход ${g.tdee} + набор. Расход: ${g.tdeeNote}")
            }
        }
        item {
            Block("Добавить") {
                Field("Что съел", text, { text = it }, number = false, lines = 2)
                Muted("Пример: «шаурма большая, Шаурма Шеф на Немиге; кола 0.5» или «гречка 200г, 2 яйца»")
                Line {
                    Primary(if (busy) "Ищу…" else "Посчитать КБЖУ", { FoodSearch.resolve(ctx, c) }, Modifier.weight(1f), enabled = !busy)
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Secondary("Вручную", { draft = (draft ?: emptyList()) + FoodItem(text.ifBlank { "Продукт" }, 100.0, Macro()) })
                }
                // фото еды → Claude определит блюда и вес; текст в поле выше уйдёт как подпись
                Line {
                    Secondary("📷 Сфоткать еду", {
                        val f = java.io.File(c.cacheDir, "photos").apply { mkdirs() }.let { java.io.File(it, "meal.jpg") }
                        val uri = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".files", f)
                        shotUri = uri; camera.launch(uri)
                    }, Modifier.weight(1f), enabled = !busy)
                    Secondary("Из галереи", {
                        gallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, Modifier.weight(1f), enabled = !busy)
                }
                Err(err); Note(info)
                if (err != null || info != null) Buttons {
                    Flat("Скопировать отладку", { c.copy(Claude.debugText()) }, C.muted)
                    Flat("Сбросить режим API", { Claude.startLevel = 3; err = null }, C.muted)
                }
            }
        }
        draft?.takeIf { it.isNotEmpty() }?.let { list ->
            item {
                Block("Проверь перед сохранением") {
                    list.forEachIndexed { i, it ->
                        if (i > 0) HorizontalDivider(color = C.line)
                        key(i, it.name) {
                            DraftItem(it,
                                { n -> draft = list.toMutableList().also { l -> l[i] = n } },
                                { draft = list.filterIndexed { j, _ -> j != i } })
                        }
                    }
                    HorizontalDivider(color = C.line)
                    Stat("Итого", list.fold(Macro()) { a, b -> a + b.total }.line())
                    Line {
                        Primary("Сохранить", {
                            val ts = if (date == ctx.today) System.currentTimeMillis()
                            else date.atTime(LocalTime.NOON).atZone(ZONE).toInstant().toEpochMilli()
                            MEAL.save(s, Meal(text.ifBlank { list.joinToString { it.name } }, list), ts = ts)
                            FoodModule.remember(s, list)
                            FoodSearch.clear()
                        }, Modifier.weight(1f), enabled = list.isNotEmpty())
                        Flat("Отмена", { draft = null }, C.muted)
                    }
                }
            }
        }
        val meals = FoodModule.meals(s, date).reversed()
        if (meals.isNotEmpty()) item { Muted("Записано") }
        items(meals, key = { it.first.id }) { (e, m) ->
            val total = m.items.fold(Macro()) { a, b -> a + b.total }
            Block(HHMM.format(Instant.ofEpochMilli(e.ts).atZone(ZONE)) + "  ·  ${total.kcal.i()} ккал") {
                Text(m.text, style = MaterialTheme.typography.bodyMedium)
                m.items.forEach { Stat("${it.name}, ${it.grams.r1()} г", "${it.total.kcal.i()} ккал") }
                Line {
                    Muted("Б ${total.p.i()} · Ж ${total.f.i()} · У ${total.c.i()}", Modifier.weight(1f))
                    DeleteButton("приём пищи") { s.delete(e.id) }
                }
            }
        }
    }
}

@Composable
private fun DraftItem(item: FoodItem, onChange: (FoodItem) -> Unit, onRemove: () -> Unit) {
    var manual by remember { mutableStateOf(item.per100 == Macro()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Line {
            Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            NumBound("Масса", item.grams, { g -> onChange(item.copy(grams = g)) }, Modifier.width(110.dp), suffix = "г")
        }
        Text(item.total.line(), style = MaterialTheme.typography.bodyMedium)
        if (item.source.isNotBlank()) Muted(
            (when (item.conf) { "high" -> "точно"; "medium" -> "примерно"; "low" -> "оценка"; else -> "" }) + " · " + item.source,
        )
        if (manual) {
            Muted("КБЖУ на 100 г:")
            Grid2(listOf("ккал", "Б", "Ж", "У")) { k, m ->
                when (k) {
                    "ккал" -> NumBound("Ккал", item.per100.kcal, { v -> onChange(item.copy(per100 = item.per100.copy(kcal = v))) }, m)
                    "Б" -> NumBound("Белки", item.per100.p, { v -> onChange(item.copy(per100 = item.per100.copy(p = v))) }, m, "г")
                    "Ж" -> NumBound("Жиры", item.per100.f, { v -> onChange(item.copy(per100 = item.per100.copy(f = v))) }, m, "г")
                    else -> NumBound("Углеводы", item.per100.c, { v -> onChange(item.copy(per100 = item.per100.copy(c = v))) }, m, "г")
                }
            }
        }
        Buttons {
            if (!manual) Flat("Править КБЖУ", { manual = true })
            Flat("Убрать", onRemove, C.muted)
        }
    }
}

/** Фото → JPEG до 1280 px по большей стороне (качество 82): Claude хватает, трафика и токенов меньше. */
private fun photoJpeg(c: android.content.Context, uri: android.net.Uri): ByteArray? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    c.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
    val bmp = c.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val k = 1280f / maxOf(bmp.width, bmp.height)
    val scaled = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
    java.io.ByteArrayOutputStream().use { out -> scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out); out.toByteArray() }
}.getOrNull()
