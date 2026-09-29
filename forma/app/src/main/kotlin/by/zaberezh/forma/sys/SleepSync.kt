package by.zaberezh.forma.sys

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import by.zaberezh.forma.Forma
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.SETTINGS
import by.zaberezh.forma.core.r1
import by.zaberezh.forma.core.sleep.NIGHT
import by.zaberezh.forma.core.sleep.SleepModule
import by.zaberezh.forma.core.sleep.detectNight
import by.zaberezh.forma.core.sleep.nightId
import by.zaberezh.forma.core.sleep.nightWindow
import by.zaberezh.forma.core.store.ZONE
import by.zaberezh.forma.core.store.today
import java.time.LocalDate

/** Сон по журналу экрана: выключение экрана / разблокировка (Android UsageStats). */
object SleepSync {
    fun hasAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Пересчитать последние [days] ночей (журнал Android хранится ~неделю). */
    fun sync(c: Context, days: Long = 7) {
        if (!hasAccess(c)) return
        val s = Forma.store
        val today = today()
        val from = nightWindow(today.minusDays(days - 1)).first
        val now = System.currentTimeMillis()
        val usm = c.getSystemService(UsageStatsManager::class.java)
        val raw = mutableListOf<Pair<Long, Int>>()
        val evs = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (evs.hasNextEvent()) {
            evs.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.SCREEN_INTERACTIVE || e.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
                e.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) raw += e.timeStamp to e.eventType
        }
        // пользование = разблокировка; экран от уведомления без разблокировки не считается.
        val hasUnlock = raw.any { it.second == UsageEvents.Event.KEYGUARD_HIDDEN }
        val events = raw.mapNotNull { (t, type) ->
            when (type) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> t to false
                UsageEvents.Event.KEYGUARD_HIDDEN -> t to true
                else -> if (hasUnlock) null else t to true
            }
        }
        for (i in 0 until days) {
            val day: LocalDate = today.minusDays(i)
            val (wFrom, wTo) = nightWindow(day)
            val n = detectNight(events, wFrom, wTo, now) ?: continue
            val id = nightId(day)
            if (s.get(id)?.let(NIGHT::decode) != n) NIGHT.save(s, n, ts = n.end, id = id)
        }
    }
}

/** Вечернее напоминание об отбое: за 30 минут до времени отбоя под цель сна. */
object Evening {
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 3, Intent(c, EveningReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val st = SETTINGS.get(Forma.store)
        if (!st.bedReminder) { am.cancel(pi); return }
        val at = SleepModule.bedtime(Ctx(Forma.store)).minusMinutes(30)
        var ms = LocalDate.now(ZONE).atTime(at).atZone(ZONE).toInstant().toEpochMilli()
        if (ms <= System.currentTimeMillis()) ms += 24 * 3600_000L
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
    }
}

class EveningReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching {
            val ctx = Forma.ctx()
            val st = ctx.settings
            Notify.post(c, 4, "Отбой в ${SleepModule.bedtime(ctx)}",
                listOf("Цель ${st.sleepTargetH.r1()} ч сна до подъёма в %02d:%02d.".format(st.wakeHour, st.wakeMinute)))
        }
        Evening.schedule(c)
    }
}
