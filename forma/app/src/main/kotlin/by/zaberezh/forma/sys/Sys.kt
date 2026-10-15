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
        // убранные функции: таймер отдыха и будильник по парам — их каналы уведомлений больше не нужны
        listOf("timers", "wake", "wake_alarm").forEach { c.getSystemService(NotificationManager::class.java).deleteNotificationChannel(it) }
    }

    /** Готовое уведомление — с той же проверкой разрешения. */
    fun postRaw(c: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && !c.granted(Manifest.permission.POST_NOTIFICATIONS)) return
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /**
     * [tap] — что делает нажатие (по умолчанию открыть приложение), [actions] — кнопки под текстом,
     * [timeoutMs] — уведомление само исчезнет через это время.
     */
    fun post(
        c: Context, id: Int, title: String, lines: List<String>,
        tap: PendingIntent? = null, actions: List<Notification.Action> = emptyList(), timeoutMs: Long = 0,
        private: Boolean = false,
    ) {
        if (lines.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && !c.granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val pi = tap ?: PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(pi)
            .setAutoCancel(true)
        actions.forEach { b.addAction(it) }
        // личное (отметки, пропуски) на экране блокировки — только заголовок без подробностей
        if (private) b.setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(
            Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText("Открой Grind, чтобы посмотреть").build())
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        c.getSystemService(NotificationManager::class.java).notify(id, b.build())
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

/**
 * Все будильники приложения — в одном месте: запуск, перезагрузка телефона и сохранение настроек
 * зовут только это (новое напоминание достаточно добавить сюда).
 */
object Reminders {
    fun scheduleAll(c: Context) {
        listOf(Morning::schedule, Evening::schedule, Weigh::schedule, Water::schedule, IisCheck::schedule)
            .forEach { runCatching { it(c) } }   // сбой одного не мешает остальным
        // будильник по парам убран: снимаем поставленный прежней версией (класса приёмника уже нет — ищем по имени)
        runCatching {
            val old = Intent().setClassName(c, "by.zaberezh.forma.sys.WakeReceiver")
            c.getSystemService(android.app.AlarmManager::class.java).cancel(
                android.app.PendingIntent.getBroadcast(c, 11, old, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }
}

/** После перезагрузки и обновления приложения будильники AlarmManager сброшены — ставим заново. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // приёмник открыт системе — чужие приложения могут слать сюда что угодно, реагируем только на свои события
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) Reminders.scheduleAll(c)
    }
}
