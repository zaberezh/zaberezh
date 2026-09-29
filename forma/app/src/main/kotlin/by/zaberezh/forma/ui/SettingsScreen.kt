@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package by.zaberezh.forma.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.Forma
import by.zaberezh.forma.core.SETTINGS
import by.zaberezh.forma.core.ai.Claude
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.sys.Geo
import by.zaberezh.forma.sys.Morning
import by.zaberezh.forma.sys.granted
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class F(val key: String, val label: String, val suffix: String? = null)

private val PROFILE = listOf(F("age", "Возраст", "лет"), F("height", "Рост", "см"))
private val GOALS = listOf(
    F("activity", "Активность", "×BMR"), F("gain", "Темп веса", "кг/нед"),
    F("protein", "Белок", "г/кг"), F("fat", "Доля жиров", "0–1"),
    F("kcal", "Калории вручную", "ккал"),
)
private val ROUTINE = listOf(
    F("sessions", "Тренировок", "в нед"), F("visit", "Мин. визит", "мин"),
    F("hour", "Уведомление", "ч"), F("minute", "Минуты", "мин"),
    F("checkup", "Чекап каждые", "дн"),
)

@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen() {
    val ctx = rememberCtx()
    val s = ctx.store
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = ctx.settings
    val p = st.profile
    var refresh by remember { mutableIntStateOf(0) }
    val f = remember(st) {
        mutableStateMapOf(
            "age" to p.age.toString(), "height" to p.heightCm.r1(), "activity" to p.activity.toString(),
            "gain" to p.gainKgPerWeek.toString(), "protein" to p.proteinPerKg.toString(), "fat" to p.fatShare.toString(),
            "kcal" to (st.kcalOverride?.toString() ?: ""), "sessions" to st.sessionsPerWeek.toString(), "visit" to st.minVisitMin.toString(),
            "hour" to st.morningHour.toString(), "minute" to "%02d".format(st.morningMinute), "checkup" to st.checkupDays.toString(),
        )
    }
    var key by remember(st) { mutableStateOf(st.apiKey) }
    var url by remember(st) { mutableStateOf(st.apiUrl) }
    var model by remember(st) { mutableStateOf(st.model) }
    var msg by remember { mutableStateOf<String?>(null) }
    var apiMsg by remember { mutableStateOf<String?>(null) }
    var apiErr by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(listOf<String>()) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Geo.register(c); refresh++ }
    val bg = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Geo.register(c); refresh++ }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { c.contentResolver.openOutputStream(it)?.use { o -> o.write(Forma.store.exportJson().toByteArray()) }; msg = "Экспортировано" }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            msg = runCatching { c.contentResolver.openInputStream(u)!!.use { Forma.store.importJson(it.readBytes().decodeToString()) }; "Импортировано" }
                .getOrElse { it.message }
        }
    }

    fun saveAll() {
        fun d(k: String, def: Double) = f[k]?.num() ?: def
        SETTINGS.set(s, SETTINGS.get(s).copy(
            profile = p.copy(
                age = d("age", 18.0).toInt(), heightCm = d("height", 180.0), activity = d("activity", 1.375),
                gainKgPerWeek = d("gain", 0.1), proteinPerKg = d("protein", 2.0), fatShare = d("fat", 0.25),
            ),
            kcalOverride = f["kcal"]?.num()?.toInt(),
            sessionsPerWeek = d("sessions", 3.0).toInt().coerceIn(1, 5), minVisitMin = d("visit", 75.0).toInt(),
            morningHour = d("hour", 8.0).toInt().coerceIn(0, 23), morningMinute = d("minute", 0.0).toInt().coerceIn(0, 59),
            checkupDays = d("checkup", 14.0).toInt().coerceIn(7, 60),
            apiKey = key.trim(), apiUrl = url.trim(), model = model.trim().ifEmpty { "claude-opus-5-5" },
        ))
        Morning.schedule(c)
    }

    @Composable
    fun grid(list: List<F>) = Grid2(list) { x, m -> Field(x.label, f[x.key] ?: "", { f[x.key] = it }, m, suffix = x.suffix) }

    Screen {
        item {
            Block("Профиль") { grid(PROFILE) }
        }
        item {
            Block("Цели питания") {
                grid(GOALS)
                Muted("Активность 1.375 = только 3 силовые в неделю, ходьба не учитывается. Темп 0.1 кг/нед = медленный набор. " +
                    "Через 2–4 недели записей расход уточняется по реальным данным.")
            }
        }
        item {
            Block("Режим") {
                grid(ROUTINE)
                Primary("Сохранить", { saveAll(); msg = "Сохранено" }, Modifier.fillMaxWidth())
                Note(msg)
            }
        }
        item {
            Block("Claude") {
                Field("API-ключ", key, { key = it }, number = false, secret = true)
                Field("Адрес API (пусто = api.anthropic.com)", url, { url = it }, number = false)
                Field("Модель", model, { model = it }, number = false)
                Buttons {
                    Claude.MODELS.forEach { (id, _) ->
                        FilterChip(selected = model == id, onClick = { model = id }, label = { Text(id.removePrefix("claude-")) })
                    }
                }
                Claude.MODELS.forEach { (_, about) -> Muted(about) }
                Muted("Ключ от посредника работает только с его адресом — впиши адрес из его инструкции (например https://…/v1).")
                Buttons {
                    Primary("Сохранить и проверить", {
                        saveAll(); checking = true; apiMsg = null; apiErr = null
                        val k = key.trim(); val m = model.trim().ifEmpty { "claude-opus-5-5" }; val u = url.trim()
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Claude(k, m, u).ping() } }
                            checking = false
                            r.onSuccess { apiMsg = "Работает: $it" }.onFailure { apiErr = Claude.explain(it) }
                        }
                    }, enabled = key.isNotBlank() && !checking)
                    Secondary("Модели сервиса", {
                        checking = true; apiMsg = null; apiErr = null
                        val k = key.trim(); val u = url.trim()
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Claude(k, "claude-sonnet-5-5", u).models() } }
                            checking = false
                            r.onSuccess { available = it; if (it.isEmpty()) apiErr = "Сервис не вернул список моделей" }
                                .onFailure { apiErr = "Список моделей недоступен: " + Claude.explain(it) }
                        }
                    }, enabled = key.isNotBlank() && !checking)
                    if (checking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                if (available.isNotEmpty()) {
                    Muted("Доступно у сервиса (нажми, чтобы выбрать):")
                    Buttons { available.forEach { id -> FilterChip(selected = model == id, onClick = { model = id }, label = { Text(id) }) } }
                }
                Note(apiMsg); Err(apiErr)
            }
        }
        item {
            Block("Разрешения") {
                refresh.let { }
                val notif = Build.VERSION.SDK_INT < 33 || c.granted(Manifest.permission.POST_NOTIFICATIONS)
                val fine = c.granted(Manifest.permission.ACCESS_FINE_LOCATION)
                val always = c.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                val battery = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
                PermRow("Уведомления и геолокация", notif && fine) {
                    perms.launch(listOfNotNull(
                        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
                    ).toTypedArray())
                }
                HorizontalDivider(color = C.line)
                PermRow("Геолокация «Разрешить всегда»", always, enabled = fine) { bg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                HorizontalDivider(color = C.line)
                PermRow("Без ограничений батареи", battery) {
                    c.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName)))
                }
                Muted("Samsung: Настройки → Батарея → Ограничения фоновой работы — убери Forma из «спящих», иначе уведомления и геозоны могут не срабатывать.")
            }
        }
        item {
            Block("Залы") {
                st.gyms.forEachIndexed { i, g ->
                    if (i > 0) HorizontalDivider(color = C.line)
                    Column {
                        Text(g.name, style = MaterialTheme.typography.bodyLarge)
                        Muted("%.5f, %.5f · радиус %d м".format(g.lat, g.lon, g.radiusM.toInt()))
                    }
                    Secondary("Я сейчас здесь — уточнить точку", {
                        LocationServices.getFusedLocationProviderClient(c)
                            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                            .addOnSuccessListener { loc ->
                                if (loc == null) { msg = "Нет координат, попробуй ещё раз"; return@addOnSuccessListener }
                                val cur = SETTINGS.get(s)
                                SETTINGS.set(s, cur.copy(gyms = cur.gyms.map { if (it.id == g.id) it.copy(lat = loc.latitude, lon = loc.longitude) else it }))
                                Geo.register(c)
                            }
                    }, Modifier.fillMaxWidth(), enabled = c.granted(Manifest.permission.ACCESS_FINE_LOCATION))
                }
            }
        }
        item {
            Block("Данные") {
                Line {
                    Primary("Экспорт", { export.launch("forma-${ctx.today}.json") }, Modifier.weight(1f))
                    Secondary("Импорт", { importer.launch(arrayOf("application/json", "*/*")) }, Modifier.weight(1f))
                }
                Muted("Все данные хранятся только на телефоне. Экспорт — резервная копия без фото и без API-ключа.")
            }
        }
    }
}

@Composable
private fun PermRow(title: String, ok: Boolean, enabled: Boolean = true, onClick: () -> Unit) = Line {
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    if (ok) Pill("есть", C.good) else Flat("Разрешить", onClick, if (enabled) C.accent else C.muted)
}
