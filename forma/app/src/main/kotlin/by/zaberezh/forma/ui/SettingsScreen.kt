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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import by.zaberezh.forma.core.study.STUDY_PREFS
import by.zaberezh.forma.core.study.TIMETABLE
import by.zaberezh.forma.core.gym.SPLITS
import by.zaberezh.forma.core.ai.Claude
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.sys.Evening
import by.zaberezh.forma.sys.Weigh
import by.zaberezh.forma.sys.Water
import by.zaberezh.forma.sys.SleepSync
import by.zaberezh.forma.sys.Morning
import by.zaberezh.forma.sys.granted
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
    F("sessions", "Тренировок", "в нед"), F("session", "Тренировка", "мин"),
    F("hour", "Уведомление", "ч"), F("minute", "Минуты", "мин"),
    F("checkup", "Чекап каждые", "дн"),
)
private val WEIGH = listOf(
    F("wH", "Взвеситься, будни", "ч"), F("wM", "Будни", "мин"),
    F("weH", "Выходные", "ч"), F("weM", "Выходные", "мин"),
)
private val SLEEP = listOf(F("sleepH", "Цель сна", "ч"), F("wakeH", "Подъём", "ч"), F("wakeM", "Подъём", "мин"))

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
            "kcal" to (st.kcalOverride?.toString() ?: ""), "sessions" to st.sessionsPerWeek.toString(), "session" to st.sessionMin.toString(),
            "hour" to st.morningHour.toString(), "minute" to "%02d".format(st.morningMinute), "checkup" to st.checkupDays.toString(),
            "wH" to st.weighHour.toString(), "wM" to "%02d".format(st.weighMinute),
            "weH" to st.weighWeekendHour.toString(), "weM" to "%02d".format(st.weighWeekendMinute),
            "sleepH" to st.sleepTargetH.r1(), "wakeH" to st.wakeHour.toString(), "wakeM" to "%02d".format(st.wakeMinute),
        )
    }
    var key by remember(st) { mutableStateOf(st.apiKey) }
    var bedReminder by remember(st) { mutableStateOf(st.bedReminder) }
    var weekends by remember(st) { mutableStateOf(st.gymWeekends) }
    var split by remember(st) { mutableStateOf(st.split) }
    var water by remember(st) { mutableStateOf(st.waterReminder) }
    var wake by remember(st) { mutableStateOf(st.wakeAlarm) }
    var weighOn by remember(st) { mutableStateOf(st.weighReminder) }
    var url by remember(st) { mutableStateOf(st.apiUrl) }
    var model by remember(st) { mutableStateOf(st.model) }
    var msg by remember { mutableStateOf<String?>(null) }
    var apiMsg by remember { mutableStateOf<String?>(null) }
    var apiErr by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(listOf<String>()) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
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
            sessionsPerWeek = d("sessions", 3.0).toInt().coerceIn(1, 5), sessionMin = d("session", 90.0).toInt().coerceIn(40, 150),
            morningHour = d("hour", 8.0).toInt().coerceIn(0, 23), morningMinute = d("minute", 0.0).toInt().coerceIn(0, 59),
            checkupDays = d("checkup", 14.0).toInt().coerceIn(7, 60),
            gymWeekends = weekends, split = split, weighReminder = weighOn, waterReminder = water, wakeAlarm = wake,
            weighHour = d("wH", 7.0).toInt().coerceIn(0, 23), weighMinute = d("wM", 20.0).toInt().coerceIn(0, 59),
            weighWeekendHour = d("weH", 11.0).toInt().coerceIn(0, 23), weighWeekendMinute = d("weM", 0.0).toInt().coerceIn(0, 59),
            apiKey = key.trim(), apiUrl = url.trim(), model = model.trim().ifEmpty { "claude-opus-5-5" },
        ))
        Morning.schedule(c)
        Evening.schedule(c)
        Weigh.schedule(c)
        Water.schedule(c)
        by.zaberezh.forma.sys.WakeAlarm.schedule(c)
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
                Line {
                    Text("Напоминание взвеситься", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = weighOn, onCheckedChange = { weighOn = it })
                }
                grid(WEIGH)
                Line {
                    Text("Попить воды — каждый час", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = water, onCheckedChange = { water = it })
                }
                Muted("Будни — с ${st.waterWeekdayFrom}:00 до ${st.waterTo}:00, выходные — с ${st.waterWeekendFrom}:00 до ${st.waterTo}:00.")
                Line {
                    Text("Будильник по первой паре", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = wake, onCheckedChange = { wake = it })
                }
                Muted("Без звука, вибрацией: пара в 8:30 — подъём в 7:10, в 10:05 — в 8:30. " +
                    (by.zaberezh.forma.sys.WakeAlarm.next()?.let { "Следующий: " + java.time.format.DateTimeFormatter.ofPattern("EEEE, HH:mm", RU).format(it) + "." } ?: "Ближайшую неделю пар нет."))
                Line {
                    Text("Выходные — тоже дни зала", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = weekends, onCheckedChange = { weekends = it })
                }
                Text("Сплит", style = MaterialTheme.typography.bodyMedium)
                Buttons {
                    SPLITS.forEach { (id, v) -> FilterChip(selected = split == id, onClick = { split = id }, label = { Text(v.first) }) }
                }
                Muted(when (split) {
                    "ul" -> "Верх и Низ по очереди: каждая мышца ~1,5 раза в неделю. Предплечья — в обоих днях."
                    "ppl" -> "Жим, Тяга, Ноги по очереди: каждая мышца 1 раз в неделю, день длиннее по одной зоне."
                    else -> "Каждая тренировка — всё тело понемногу: мышца 3 раза в неделю."
                })
                Primary("Сохранить", { saveAll(); msg = "Сохранено" }, Modifier.fillMaxWidth())
                Note(msg)
            }
        }
        item {
            val sp = STUDY_PREFS.get(s)
            val tt = TIMETABLE.get(s)
            var group by remember(sp.group) { mutableStateOf(sp.group) }
            var loadingTt by remember { mutableStateOf(false) }
            var ttMsg by remember { mutableStateOf<String?>(null) }
            Block("Учёба") {
                Line {
                    Field("Группа БГУИР", group, { group = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f))
                    Secondary("Сохранить", { STUDY_PREFS.set(s, sp.copy(group = group)); ttMsg = "Группа сохранена — расписание обновится" },
                        enabled = group.length == 6 && group != sp.group)
                }
                Text("Подгруппа", style = MaterialTheme.typography.bodyMedium)
                Buttons {
                    listOf(0 to "Обе", 1 to "1-я", 2 to "2-я").forEach { (n, label) ->
                        FilterChip(selected = sp.subgroup == n, onClick = { STUDY_PREFS.set(s, sp.copy(subgroup = n)) }, label = { Text(label) })
                    }
                }
                Line {
                    Muted(if (tt.fetchedAt > 0) "Расписание ${tt.group}: обновлено ${java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)
                        .format(java.time.Instant.ofEpochMilli(tt.fetchedAt).atZone(java.time.ZoneId.systemDefault()))}" else "Расписание не загружено",
                        Modifier.weight(1f))
                    if (loadingTt) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Flat("Обновить", {
                        loadingTt = true; ttMsg = null
                        scope.launch { downloadTimetable(s).onSuccess { ttMsg = "Обновлено" }.onFailure { ttMsg = it.message }; loadingTt = false }
                    })
                }
                Note(ttMsg)
                Muted("Источник — открытое расписание ИИС БГУИР (iis.bsuir.by).")
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
                val battery = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
                PermRow("Уведомления", notif) {
                    perms.launch(listOfNotNull(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null).toTypedArray())
                }
                HorizontalDivider(color = C.line)
                PermRow("История использования (сон)", SleepSync.hasAccess(c)) {
                    c.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                HorizontalDivider(color = C.line)
                PermRow("Без ограничений батареи", battery) {
                    c.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName)))
                }
                Muted("Samsung: Настройки → Батарея → Ограничения фоновой работы — убери Grind из «спящих», иначе уведомления могут не приходить.")
            }
        }
        item {
            Block("Данные") {
                Line {
                    Primary("Экспорт", { export.launch("forma-${ctx.today}.json") }, Modifier.weight(1f))
                    Secondary("Импорт", { importer.launch(arrayOf("application/json", "*/*")) }, Modifier.weight(1f))
                }
                Muted("Все данные хранятся только на этом телефоне и не попадают в облачные копии. Перенести на новый телефон — «Экспорт» здесь, «Импорт» там (без API-ключа).")
            }
        }
    }
}

@Composable
private fun PermRow(title: String, ok: Boolean, enabled: Boolean = true, onClick: () -> Unit) = Line {
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    if (ok) Pill("есть", C.good) else Flat("Разрешить", onClick, if (enabled) C.accent else C.muted)
}
