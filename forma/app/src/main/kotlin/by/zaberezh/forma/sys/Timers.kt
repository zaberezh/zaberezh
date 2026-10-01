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
import by.zaberezh.forma.Forma
import by.zaberezh.forma.R
import by.zaberezh.forma.core.study.Focus
import by.zaberezh.forma.core.study.LAB

/**
 * Короткие таймеры с вибрацией (β): отдых между подходами и фокус-сессия над лабой.
 * Срабатывают и при выключенном экране (точный будильник), звук не играют — тихий канал + вибрация.
 */
object Timers {
    private const val CH = "timers"
    const val REST = 30
    const val FOCUS = 31

    fun channel(c: Context) {
        val ch = NotificationChannel(CH, "Таймеры: отдых и фокус", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); description = "Конец отдыха между подходами и фокус-сессий"
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

    // ---------- фокус над лабой ----------
    fun startFocus(c: Context, labId: String) {
        val r = Focus.start(Forma.store, labId)
        at(c, r.endsAt(), pi(c, FOCUS, FocusReceiver::class.java))
    }

    fun stopFocus(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(pi(c, FOCUS, FocusReceiver::class.java))
        Focus.stop(Forma.store)
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

class FocusReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val s = Forma.store
        val run = Focus.active(s) ?: return
        val done = Focus.stop(s) ?: return
        val lab = s.get(run.lab)?.let { runCatching { LAB.decode(it) }.getOrNull() }
        Buzz.alarm(c, longArrayOf(0, 500, 200, 500))
        Timers.note(c, Timers.FOCUS, "Фокус окончен — ${done.minutes} мин",
            (lab?.let { "${it.subject} · ${it.name} · " } ?: "") + "перерыв 5 минут, потом можно ещё круг", 10 * 60_000)
    }
}
