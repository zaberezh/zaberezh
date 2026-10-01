package by.zaberezh.forma.sys

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import java.time.format.DateTimeFormatter

/**
 * Будильник по первой паре — без звука, только вибрация. 8:30 → 7:10, 10:05 → 8:30 (см. Study.wakeAt).
 * Ставится системным «будильником» (значок в строке состояния), перепланируется после срабатывания,
 * при запуске, перезагрузке и обновлении расписания.
 */
object WakeAlarm {
    private const val CH = "wake"
    private const val ID = 11
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    // ~40 секунд: вибрация 1,2 с, пауза 0,8 с
    private val PATTERN = LongArray(41) { if (it == 0) 0L else if (it % 2 == 1) 1200L else 800L }

    private fun pi(c: Context) = PendingIntent.getBroadcast(c, ID, Intent(c, WakeReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun channel(c: Context) {
        val ch = NotificationChannel(CH, "Будильник по парам", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)   // вибрацию ведём сами — длинную
            description = "Подъём к первой паре, без звука"
        }
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
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

    @Suppress("DEPRECATION")
    private fun vibrator(c: Context): Vibrator =
        if (android.os.Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java).defaultVibrator
        else c.getSystemService(Vibrator::class.java)

    fun ring(c: Context) {
        val s = Forma.store
        val tt = TIMETABLE.get(s)
        val today = java.time.LocalDate.now(ZONE)
        val first = Bsuir.on(tt, today, STUDY_PREFS.get(s).subgroup).filter { !it.type.startsWith("Конс", true) }.minByOrNull { it.start }
        runCatching { vibrator(c).vibrate(VibrationEffect.createWaveform(PATTERN, -1)) }
        val stop = PendingIntent.getBroadcast(c, ID + 1, Intent(c, WakeStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Подъём — пара в ${first?.start ?: ""}")
            .setContentText(first?.let { listOf(it.title, it.type, it.rooms.joinToString(", ")).filter(String::isNotBlank).joinToString(" · ") } ?: "")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(stop).setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, "Встал", stop).build())
            .setAutoCancel(true)
            .build()
        Notify.postRaw(c, ID, n)
    }

    fun stop(c: Context) {
        runCatching { vibrator(c).cancel() }
        c.getSystemService(NotificationManager::class.java).cancel(ID)
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
