package by.zaberezh.forma.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.sleep.SleepModule
import by.zaberezh.forma.core.sleep.dur
import by.zaberezh.forma.core.sleep.hm
import by.zaberezh.forma.sys.SleepSync
import by.zaberezh.forma.sys.Evening
import by.zaberezh.forma.core.SETTINGS
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.TextStyle

@Composable
fun SleepBlock(ctx: Ctx) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(SleepSync.hasAccess(c)) }
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        access = SleepSync.hasAccess(c)
        if (access) scope.launch { withContext(Dispatchers.IO) { runCatching { SleepSync.sync(c) } } }
    }
    LaunchedEffect(access) { if (access) withContext(Dispatchers.IO) { runCatching { SleepSync.sync(c) } } }
    val target = ctx.settings.sleepTargetH
    Block("Сон", trailing = { Pill("цель ${target.r1()} ч", C.muted) }) {
        if (!access) {
            Muted("Сон считается сам: засыпание — последнее выключение экрана вечером, подъём — первая разблокировка утром. " +
                "Нужен доступ «История использования» для Grind.")
            Primary("Дать доступ", { grant.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }, Modifier.fillMaxWidth())
            return@Block
        }
        val n = SleepModule.lastNight(ctx.store, ctx.today)
        if (n == null) Muted("Прошлая ночь появится после первой разблокировки утром.")
        else {
            val h = n.minutes / 60; val m = n.minutes % 60
            BigValue("$h:${"%02d".format(m)}", "ч", "${hm(n.start)} → ${hm(n.end)}" +
                if (n.wakeups > 0) " · ночью брал телефон ${n.wakeups} раз" else "")
        }
        SleepModule.stats(ctx, ctx.today.minusDays(6), ctx.today)?.let { st ->
            Stat("Среднее за неделю", dur(st.avgMin), if (st.avgMin >= target * 60 - 30) C.good else C.warn)
            Stat("Отбой в среднем", "${st.bedAvg} ±${st.bedSdMin} мин", if (st.bedSdMin <= 60) C.text else C.warn)
        }
        Stat("Отбой сегодня", SleepModule.bedtime(ctx).toString())
        val list = SleepModule.nights(ctx.store, ctx.today.minusDays(6), ctx.today).reversed()
        if (list.size > 1) Muted(list.joinToString("\n") { (d, x) ->
            "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, RU)}  ${hm(x.start)}–${hm(x.end)}  ${dur(x.minutes)}"
        })
    }
}

/** Кратко о сне для главного экрана. */
@Composable
fun SleepSummary(ctx: Ctx, onOpen: () -> Unit) {
    val c = LocalContext.current
    val access = remember { SleepSync.hasAccess(c) }
    LaunchedEffect(Unit) { if (access) withContext(Dispatchers.IO) { runCatching { SleepSync.sync(c) } } }
    val n = SleepModule.lastNight(ctx.store, ctx.today)
    val st = SleepModule.stats(ctx, ctx.today.minusDays(6), ctx.today)
    val target = ctx.settings.sleepTargetH
    Block("Сон", trailing = { Flat("Подробнее", onOpen) }) {
        when {
            !access -> Muted("Нужен доступ «История использования» — во вкладке «Сон».")
            n == null -> Muted("Прошлая ночь появится после первой разблокировки утром.")
            else -> Stat("Прошлая ночь", "${hm(n.start)} → ${hm(n.end)} · ${dur(n.minutes)}",
                if (n.minutes >= target * 60 - 30) C.good else C.warn)
        }
        st?.let { Stat("Среднее за неделю", dur(it.avgMin)) }
        Stat("Отбой сегодня", SleepModule.bedtime(ctx).toString())
    }
}

/** Вкладка «Сон»: ночь, неделя, история и настройки режима. */
@Composable
fun SleepScreen() {
    val ctx = rememberCtx()
    val st = ctx.settings
    Screen {
        item { SleepBlock(ctx) }
        item {
            val nights = SleepModule.nights(ctx.store, ctx.today.minusDays(13), ctx.today).reversed()
            if (nights.isNotEmpty()) Block("Последние ночи") {
                nights.forEach { (d, x) ->
                    Stat("${d.dayOfWeek.getDisplayName(TextStyle.SHORT, RU)} ${d.dayOfMonth}  ${hm(x.start)}–${hm(x.end)}",
                        dur(x.minutes) + if (x.wakeups > 0) " · ${x.wakeups}×" else "",
                        if (x.minutes >= st.sleepTargetH * 60 - 30) C.good else C.warn)
                }
            }
        }
        item {
            var target by remember(st) { mutableStateOf(st.sleepTargetH.r1()) }
            var wakeH by remember(st) { mutableStateOf(st.wakeHour.toString()) }
            var wakeM by remember(st) { mutableStateOf("%02d".format(st.wakeMinute)) }
            var remind by remember(st) { mutableStateOf(st.bedReminder) }
            val c = LocalContext.current
            Block("Режим сна") {
                Line {
                    Field("Цель", target, { target = it }, Modifier.weight(1f), suffix = "ч")
                    Field("Подъём", wakeH, { wakeH = it }, Modifier.weight(1f), suffix = "ч")
                    Field("", wakeM, { wakeM = it }, Modifier.weight(1f), suffix = "мин")
                }
                Line {
                    Text("Напоминание об отбое за 30 мин", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = remind, onCheckedChange = { remind = it })
                }
                Primary("Сохранить", {
                    SETTINGS.set(ctx.store, SETTINGS.get(ctx.store).copy(
                        sleepTargetH = (target.num() ?: 8.0).coerceIn(5.0, 11.0),
                        wakeHour = (wakeH.num()?.toInt() ?: 7).coerceIn(0, 23), wakeMinute = (wakeM.num()?.toInt() ?: 30).coerceIn(0, 59),
                        bedReminder = remind,
                    ))
                    Evening.schedule(c)
                }, Modifier.fillMaxWidth())
                Muted("Отбой = подъём − цель сна. " +
                    "Засыпание — по последнему выключению экрана, подъём — по первой разблокировке.")
            }
        }
    }
}
