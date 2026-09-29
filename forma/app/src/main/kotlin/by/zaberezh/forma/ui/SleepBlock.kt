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
                "Нужен доступ «История использования» для Forma.")
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
