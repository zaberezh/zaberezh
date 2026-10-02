package by.zaberezh.forma.sys

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import by.zaberezh.forma.R

/**
 * Таймер отдыха между подходами (β) с вибрацией.
 * Срабатывают и при выключенном экране (точный будильник), звук не играют — тихий канал + вибрация.
 */
object Timers {
    private const val CH = "timers"
    const val REST = 30

    fun channel(c: Context) {
        val ch = NotificationChannel(CH, "Таймер отдыха", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); description = "Конец отдыха между подходами"
        }
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun pi(c: Context, id: Int, cls: Class<*>, label: String = "") = PendingIntent.getBroadcast(c, id,
        Intent(c, cls).putExtra("label", label), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun at(c: Context, ms: Long, p: PendingIntent) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
    }

    // ---------- отдых между подходами ----------
    fun startRest(c: Context, seconds: Int, label: String) =
        at(c, System.currentTimeMillis() + seconds * 1000L, pi(c, REST, RestReceiver::class.java, label))

    fun cancelRest(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(pi(c, REST, RestReceiver::class.java))
        c.getSystemService(NotificationManager::class.java).cancel(REST)
    }

    fun note(c: Context, id: Int, title: String, text: String, timeoutMs: Long) {
        val tap = PendingIntent.getActivity(c, id, Intent(c, by.zaberezh.forma.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        Notify.postRaw(c, id, Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setContentIntent(tap).setAutoCancel(true).setTimeoutAfter(timeoutMs).build())
    }
}

class RestReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Buzz.alarm(c, longArrayOf(0, 300, 150, 300, 150, 500))
        Timers.note(c, Timers.REST, "Отдых окончен", i.getStringExtra("label").orEmpty().ifBlank { "Следующий подход" }, 90_000)
    }
}
