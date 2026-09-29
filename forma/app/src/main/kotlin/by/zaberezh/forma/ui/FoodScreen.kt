package by.zaberezh.forma.ui

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun FoodScreen() {
    val ctx = rememberCtx()
    val s = ctx.store
    var date by remember { mutableStateOf(ctx.today) }
    var text by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<List<FoodItem>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun resolve() {
        val q = text.trim(); if (q.isEmpty()) return
        busy = true; err = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    LibraryResolver(s).resolve(q)
                        ?: ctx.settings.apiKey.takeIf { it.isNotBlank() }?.let { Claude(it, ctx.settings.model).foods(q) }
                        ?: error("Нет в библиотеке, а API-ключ Claude не задан (Настройки). Можно ввести вручную ниже.")
                }
            }
            busy = false
            r.onSuccess { draft = (draft ?: emptyList()) + it }.onFailure { err = it.message ?: it.toString() }
        }
    }

    Screen {
        item {
            val t = FoodModule.dayTotal(s, date); val g = FoodModule.targets(ctx)
            Block {
                Line {
                    TextButton(onClick = { date = date.minusDays(1) }) { Text("◀") }
                    Text(if (date == ctx.today) "Сегодня" else date.toString())
                    TextButton(onClick = { if (date < ctx.today) date = date.plusDays(1) }) { Text("▶") }
                }
                Text("${t.kcal.i()} / ${g.kcal} ккал")
                Text("Б ${t.p.i()}/${g.p}   Ж ${t.f.i()}/${g.f}   У ${t.c.i()}/${g.c}")
                Muted("TDEE ${g.tdee}: ${g.tdeeNote}")
            }
        }
        item {
            Block("Что съел") {
                Field("напр.: шаурма большая Шаурма Шеф на Немиге, кола 0.5", text, { text = it }, number = false, lines = 2)
                Line {
                    Button(onClick = { resolve() }, enabled = !busy) { Text("Посчитать") }
                    OutlinedButton(onClick = { draft = (draft ?: emptyList()) + FoodItem(text.ifBlank { "продукт" }, 100.0, Macro()) }) { Text("Вручную") }
                    if (busy) CircularProgressIndicator()
                }
                Err(err)
            }
        }
        val lib = FoodModule.library(s).take(15)
        if (lib.isNotEmpty()) item {
            LazyRow {
                items(lib) { f ->
                    AssistChip(onClick = { draft = (draft ?: emptyList()) + FoodItem(f.name, f.grams, f.per100, f.source, "high") },
                        label = { Text("${f.name} ${f.grams.r1()}г") })
                }
            }
        }
        draft?.let { items ->
            item {
                Block("Проверь и сохрани") {
                    items.forEachIndexed { i, it -> key(i, it.name) { DraftItem(it, { n -> draft = items.toMutableList().also { l -> l[i] = n } }, { draft = items.filterIndexed { j, _ -> j != i } }) } }
                    Text("Итого: " + items.fold(Macro()) { a, b -> a + b.total }.short())
                    Line {
                        Button(onClick = {
                            val ts = if (date == ctx.today) System.currentTimeMillis() else date.atTime(LocalTime.NOON).atZone(ZONE).toInstant().toEpochMilli()
                            MEAL.save(s, Meal(text.ifBlank { items.joinToString { it.name } }, items), ts = ts)
                            FoodModule.remember(s, items)
                            draft = null; text = ""
                        }, enabled = items.isNotEmpty()) { Text("Сохранить") }
                        TextButton(onClick = { draft = null }) { Text("Отмена") }
                    }
                }
            }
        }
        items(FoodModule.meals(s, date).reversed(), key = { it.first.id }) { (e, m) ->
            Block {
                Line {
                    Text(HHMM.format(java.time.Instant.ofEpochMilli(e.ts).atZone(ZONE)) + "  " + m.text, Modifier.weight(1f))
                    TextButton(onClick = { s.delete(e.id) }) { Text("×") }
                }
                m.items.forEach { Muted("${it.name} ${it.grams.r1()}г — ${it.total.short()}") }
                Text(m.items.fold(Macro()) { a, b -> a + b.total }.short())
            }
        }
    }
}

@Composable
private fun DraftItem(it: FoodItem, onChange: (FoodItem) -> Unit, onRemove: () -> Unit) {
    var manual by remember { mutableStateOf(it.per100 == Macro()) }
    Line {
        Text(it.name, Modifier.weight(1f))
        NumBound("г", it.grams, { g -> onChange(it.copy(grams = g)) }, Modifier.weight(0.6f))
        TextButton(onClick = onRemove) { Text("×") }
    }
    Muted(it.total.short() + (if (it.source.isNotBlank()) " · ${it.conf} · ${it.source}" else ""))
    if (manual) {
        Muted("на 100 г:")
        Line {
            NumBound("ккал", it.per100.kcal, { v -> onChange(it.copy(per100 = it.per100.copy(kcal = v))) }, Modifier.weight(1f))
            NumBound("Б", it.per100.p, { v -> onChange(it.copy(per100 = it.per100.copy(p = v))) }, Modifier.weight(1f))
            NumBound("Ж", it.per100.f, { v -> onChange(it.copy(per100 = it.per100.copy(f = v))) }, Modifier.weight(1f))
            NumBound("У", it.per100.c, { v -> onChange(it.copy(per100 = it.per100.copy(c = v))) }, Modifier.weight(1f))
        }
    } else TextButton(onClick = { manual = true }) { Text("править КБЖУ на 100 г") }
}
