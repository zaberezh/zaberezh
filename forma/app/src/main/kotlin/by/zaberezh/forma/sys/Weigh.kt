package by.zaberezh.forma.sys

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import by.zaberezh.forma.Forma
import by.zaberezh.forma.core.SETTINGS
import by.zaberezh.forma.core.body.WEIGHT
import by.zaberezh.forma.core.body.nextWeighTime
import by.zaberezh.forma.core.daily.nextWaterTime
import by.zaberezh.forma.core.store.ZONE
import by.zaberezh.forma.core.store.today
import java.time.LocalDateTime

/** Напоминание взвеситься: по умолчанию 7:20 в будни и 11:00 в выходные. */
object Weigh {
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 6, Intent(c, WeighReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val st = SETTINGS.get(Forma.store)
        if (!st.weighReminder) { am.cancel(pi); return }
        val at = nextWeighTime(st, LocalDateTime.now(ZONE)).atZone(ZONE).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class WeighReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching {
            val weighed = WEIGHT.all(Forma.store).lastOrNull()?.first?.day == today()
            if (!weighed) Notify.post(c, 6, "Взвесься", listOf("Натощак, после туалета, до еды и воды. Запиши на «Сегодня»."))
        }
        Weigh.schedule(c)
    }
}

/** «Попей воды» — каждый час: будни с 16 до 23, выходные с 11 до 23 (настраивается). */
object Water {
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 7, Intent(c, WaterReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val st = SETTINGS.get(Forma.store)
        if (!st.waterReminder) { am.cancel(pi); return }
        val at = nextWaterTime(st, LocalDateTime.now(ZONE)).atZone(ZONE).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class WaterReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching { Notify.post(c, 7, "Попей воды", listOf("Стакан воды — 200–250 мл.")) }
        Water.schedule(c)
    }
}
