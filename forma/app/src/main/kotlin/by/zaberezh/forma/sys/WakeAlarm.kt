package by.zaberezh.forma.sys

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import by.zaberezh.forma.Forma
import by.zaberezh.forma.R
import by.zaberezh.forma.core.SETTINGS
import by.zaberezh.forma.core.store.ZONE
import by.zaberezh.forma.core.study.Bsuir
import by.zaberezh.forma.core.study.STUDY_PREFS
import by.zaberezh.forma.core.study.Study
import by.zaberezh.forma.core.study.TIMETABLE
import by.zaberezh.forma.ui.MainActivity
import java.time.LocalDateTime

/**
 * Будильник по первой паре — без звука, только вибрация. 8:30 → 7:10, 10:05 → 8:30 (см. Study.wakeAt).
 * Ставится системным «будильником» (значок в строке состояния), перепланируется после срабатывания,
 * при запуске, перезагрузке и обновлении расписания.
 */
object WakeAlarm {
    /** Новый id канала: у прежнего («wake») вибрация была выключена, а настройки канала после создания не меняются. */
    private const val CH = "wake_alarm"
    private const val ID = 11
    /** Сколько будит, если не нажать «Встал»: 5 минут импульсов 1,2 с через 0,8 с. */
    private const val RING_MS = 5 * 60_000L
    private val PATTERN = longArrayOf(0, 1200, 800)

    private fun pi(c: Context) = PendingIntent.getBroadcast(c, ID, Intent(c, WakeReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun stopPi(c: Context) = PendingIntent.getBroadcast(c, ID + 1, Intent(c, WakeStopReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("wake")
        val ch = NotificationChannel(CH, "Будильник по парам", NotificationManager.IMPORTANCE_HIGH).apply {
            // без звука, но с атрибутами будильника: вибрирует система (не зависит от жизни процесса),
            // «Не беспокоить» будильники пропускает
            setSound(null, Buzz.ALARM_AUDIO)
            enableVibration(true); vibrationPattern = PATTERN
            description = "Подъём к первой паре: без звука, только вибрация"
        }
        nm.createNotificationChannel(ch)
    }

    /** Время следующего будильника или null (выключен / пар нет). */
    fun next(now: LocalDateTime = LocalDateTime.now(ZONE)): LocalDateTime? {
        val s = Forma.store
        if (!SETTINGS.get(s).wakeAlarm) return null
        return Study.nextWake(TIMETABLE.get(s), now, STUDY_PREFS.get(s).subgroup)
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val at = runCatching { next() }.getOrNull()
        if (at == null) { am.cancel(pi(c)); return }
        val ms = at.atZone(ZONE).toInstant().toEpochMilli()
        val show = PendingIntent.getActivity(c, ID, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(ms, show), pi(c)) }
            .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi(c)) }
    }

    /**
     * Две вибрации-страховки: своя (будильная — работает и в беззвучном режиме) и «настойчивая» вибрация уведомления
     * от системы (повторяется, пока не нажать «Встал», даже если Android выгрузит приложение). Через 5 минут — тишина.
     */
    fun ring(c: Context) {
        val s = Forma.store
        val tt = TIMETABLE.get(s)
        val today = java.time.LocalDate.now(ZONE)
        val first = Bsuir.on(tt, today, STUDY_PREFS.get(s).subgroup).filter { !it.type.startsWith("Конс", true) }.minByOrNull { it.start }
        Buzz.alarm(c, PATTERN, repeat = 0)
        val stop = stopPi(c)
        c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RING_MS, stop)
        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Подъём — пара в ${first?.start ?: ""}")
            .setContentText(first?.let { listOf(it.short, it.type, it.rooms.joinToString(", ")).filter(String::isNotBlank).joinToString(" · ") } ?: "")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(stop).setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, "Встал", stop).build())
            .setAutoCancel(true)
            .setTimeoutAfter(RING_MS)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
        Notify.postRaw(c, ID, n)
    }

    fun stop(c: Context) {
        Buzz.stop(c)
        c.getSystemService(NotificationManager::class.java).cancel(ID)
        c.getSystemService(AlarmManager::class.java).cancel(stopPi(c))
    }
}

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching { WakeAlarm.ring(c) }
        WakeAlarm.schedule(c)
    }
}

class WakeStopReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { WakeAlarm.stop(c) }
}
