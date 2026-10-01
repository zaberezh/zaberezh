package by.zaberezh.forma.sys

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import by.zaberezh.forma.Forma
import by.zaberezh.forma.R
import by.zaberezh.forma.core.SETTINGS
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.report.Checkup
import by.zaberezh.forma.core.store.ZONE
import by.zaberezh.forma.core.store.today
import by.zaberezh.forma.ui.MainActivity
import java.time.LocalDate

fun Context.granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

object Notify {
    private const val CH = "main"

    fun channels(c: Context) {
        c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Напоминания", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun post(c: Context, id: Int, title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && !c.granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

/** Утреннее уведомление: будильник на заданное время, перепланируется каждый день. */
object Morning {
    fun schedule(c: Context) {
        val s = SETTINGS.get(Forma.store)
        var at = LocalDate.now(ZONE).atTime(s.morningHour, s.morningMinute).atZone(ZONE).toInstant().toEpochMilli()
        if (at <= System.currentTimeMillis()) at += 24 * 3600_000L
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 1, Intent(c, MorningReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class MorningReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching { SleepSync.sync(c) }
        runCatching { Notify.post(c, 1, "Сегодня", Checkup.morning(Forma.ctx())) }
        Morning.schedule(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Morning.schedule(c)
        Evening.schedule(c)
        Weigh.schedule(c)
        Water.schedule(c)
    }
}
