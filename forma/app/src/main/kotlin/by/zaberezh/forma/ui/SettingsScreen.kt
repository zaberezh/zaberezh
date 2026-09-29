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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import by.zaberezh.forma.Forma
import by.zaberezh.forma.core.SETTINGS
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.sys.Geo
import by.zaberezh.forma.sys.Morning
import by.zaberezh.forma.sys.granted
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen() {
    val ctx = rememberCtx()
    val s = ctx.store
    val c = LocalContext.current
    val st = ctx.settings
    val p = st.profile
    var refresh by remember { mutableIntStateOf(0) }
    val f = remember(st) {
        mutableStateMapOf(
            "age" to p.age.toString(), "height" to p.heightCm.r1(), "activity" to p.activity.toString(),
            "gain" to p.gainKgPerWeek.toString(), "protein" to p.proteinPerKg.toString(), "fat" to p.fatShare.toString(),
            "kcal" to (st.kcalOverride?.toString() ?: ""), "sessions" to st.sessionsPerWeek.toString(), "visit" to st.minVisitMin.toString(),
            "hour" to st.morningHour.toString(), "minute" to st.morningMinute.toString(), "checkup" to st.checkupDays.toString(),
        )
    }
    var key by remember(st) { mutableStateOf(st.apiKey) }
    var model by remember(st) { mutableStateOf(st.model) }
    var msg by remember { mutableStateOf<String?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Geo.register(c); refresh++ }
    val bg = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Geo.register(c); refresh++ }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { c.contentResolver.openOutputStream(it)?.use { o -> o.write(Forma.store.exportJson().toByteArray()) }; msg = "Экспортировано" }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u -> msg = runCatching { c.contentResolver.openInputStream(u)!!.use { Forma.store.importJson(it.readBytes().decodeToString()) }; "Импортировано" }.getOrElse { it.message } }
    }

    @Composable
    fun num(k: String, label: String) = Field(label, f[k] ?: "", { f[k] = it })

    Screen {
        item {
            Block("Профиль и цели") {
                num("age", "Возраст"); num("height", "Рост, см")
                num("activity", "Активность (×BMR), 1.55 = 3 трен. + ходьба")
                num("gain", "Темп веса, кг/нед (0.1 = медленный набор)")
                num("protein", "Белок, г/кг"); num("fat", "Доля жиров в калориях (0.25)")
                num("kcal", "Калории вручную (пусто = авто)")
                num("sessions", "Тренировок в неделю"); num("visit", "Мин. визит в зал, мин")
                num("hour", "Утреннее уведомление: час"); num("minute", "…минуты")
                num("checkup", "Чекап каждые N дней")
                Field("Claude API-ключ", key, { key = it }, number = false)
                Field("Модель", model, { model = it }, number = false)
                Muted("Ключ: console.anthropic.com. Поиск КБЖУ ≈ 1 запрос с веб-поиском; повторные блюда берутся из библиотеки бесплатно.")
                Button(onClick = {
                    fun d(k: String, def: Double) = f[k]?.num() ?: def
                    SETTINGS.set(s, st.copy(
                        profile = p.copy(age = d("age", 18.0).toInt(), heightCm = d("height", 180.0), activity = d("activity", 1.55),
                            gainKgPerWeek = d("gain", 0.1), proteinPerKg = d("protein", 2.0), fatShare = d("fat", 0.25)),
                        kcalOverride = f["kcal"]?.num()?.toInt(),
                        sessionsPerWeek = d("sessions", 3.0).toInt().coerceIn(1, 5), minVisitMin = d("visit", 75.0).toInt(),
                        morningHour = d("hour", 8.0).toInt().coerceIn(0, 23), morningMinute = d("minute", 0.0).toInt().coerceIn(0, 59),
                        checkupDays = d("checkup", 14.0).toInt().coerceIn(7, 60), apiKey = key.trim(), model = model.trim().ifEmpty { "claude-opus-5-5" },
                    ))
                    Morning.schedule(c); msg = "Сохранено"
                }) { Text("Сохранить") }
                Err(msg)
            }
        }
        item {
            Block("Разрешения") {
                refresh.let { }
                val notif = Build.VERSION.SDK_INT < 33 || c.granted(Manifest.permission.POST_NOTIFICATIONS)
                val fine = c.granted(Manifest.permission.ACCESS_FINE_LOCATION)
                val always = c.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                val battery = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
                Text("Уведомления: ${if (notif) "да" else "нет"} · Геолокация: ${if (fine) "да" else "нет"} · «Всегда»: ${if (always) "да" else "нет"} · Без ограничений батареи: ${if (battery) "да" else "нет"}")
                Button(onClick = {
                    perms.launch(listOfNotNull(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null).toTypedArray())
                }) { Text("1. Уведомления + геолокация") }
                Button(onClick = { bg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }, enabled = fine) { Text("2. Геолокация «Разрешить всегда»") }
                OutlinedButton(onClick = {
                    c.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName)))
                }) { Text("3. Отключить экономию батареи (Samsung)") }
                Muted("Samsung: Настройки → Батарея → Ограничения фоновых процессов — убери Forma из «спящих», иначе утренние уведомления и геозоны могут не срабатывать.")
            }
        }
        item {
            Block("Залы (геозоны)") {
                st.gyms.forEach { g ->
                    Text(g.name)
                    Muted("${g.lat}, ${g.lon}, радиус ${g.radiusM.toInt()} м")
                    OutlinedButton(enabled = c.granted(Manifest.permission.ACCESS_FINE_LOCATION), onClick = {
                        LocationServices.getFusedLocationProviderClient(c)
                            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                            .addOnSuccessListener { loc ->
                                if (loc == null) { msg = "Нет координат, попробуй на улице"; return@addOnSuccessListener }
                                val cur = SETTINGS.get(s)
                                SETTINGS.set(s, cur.copy(gyms = cur.gyms.map { if (it.id == g.id) it.copy(lat = loc.latitude, lon = loc.longitude) else it }))
                                Geo.register(c); msg = "${g.name}: координаты обновлены"
                            }
                    }) { Text("Я сейчас здесь — уточнить точку") }
                }
            }
        }
        item {
            Block("Данные") {
                Line {
                    Button(onClick = { export.launch("forma-${ctx.today}.json") }) { Text("Экспорт") }
                    OutlinedButton(onClick = { import.launch(arrayOf("application/json", "*/*")) }) { Text("Импорт") }
                }
                Muted("Все данные хранятся только на телефоне. Экспорт — резервная копия (фото не включаются).")
            }
        }
    }
}
