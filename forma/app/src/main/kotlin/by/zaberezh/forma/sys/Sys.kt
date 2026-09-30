package by.zaberezh.forma.sys

import android.Manifest
import android.annotation.SuppressLint
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
import by.zaberezh.forma.core.gym.VISIT
import by.zaberezh.forma.core.gym.Visit
import by.zaberezh.forma.core.report.Checkup
import by.zaberezh.forma.core.store.ZONE
import by.zaberezh.forma.core.store.today
import by.zaberezh.forma.ui.MainActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
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
        Geo.register(c) // геозоны сбрасываются после перезагрузки
    }
}

/** Геозоны залов: вход/выход -> визит с длительностью. */
object Geo {
    private const val KEY = "geo.enter"

    private fun intent(c: Context) = PendingIntent.getBroadcast(c, 2, Intent(c, GeoReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    @SuppressLint("MissingPermission")
    fun register(c: Context) {
        if (!c.granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val gyms = SETTINGS.get(Forma.store).gyms
        val client = LocationServices.getGeofencingClient(c)
        val pi = intent(c)
        client.removeGeofences(pi).addOnCompleteListener {
            if (gyms.isEmpty()) return@addOnCompleteListener
            val fences = gyms.map {
                Geofence.Builder().setRequestId(it.id)
                    .setCircularRegion(it.lat, it.lon, it.radiusM)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            }
            val req = GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER).addGeofences(fences).build()
            runCatching { client.addGeofences(req, pi) }
        }
    }

    fun onTransition(c: Context, gym: String, enter: Boolean, now: Long = System.currentTimeMillis()) {
        val s = Forma.store
        val open = s.kvGet(KEY)?.split("|")?.takeIf { it.size == 2 }
        if (enter) {
            // повторный вход в течение 6 ч в тот же зал — продолжение визита
            if (open == null || open[0] != gym || now - open[1].toLong() > 6 * 3600_000L) s.kvPut(KEY, "$gym|$now")
            return
        }
        if (open == null || open[0] != gym) return
        s.kvPut(KEY, null)
        val start = open[1].toLong()
        val min = (now - start) / 60_000
        if (min < 20 || min > 360) return
        VISIT.save(s, Visit(gym, start, now), ts = start)
        val sets = GymModule.workouts(s, today(), today()).sumOf { it.second.sets.size }
        if (min >= SETTINGS.get(s).minVisitMin && sets == 0)
            Notify.post(c, 2, "Зал: визит $min мин засчитан", listOf("Подходы не записаны — внеси веса, пока помнишь."))
    }
}

class GeoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val ev = GeofencingEvent.fromIntent(i) ?: return
        if (ev.hasError()) return
        val enter = when (ev.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> return
        }
        ev.triggeringGeofences?.forEach { Geo.onTransition(c, it.requestId, enter) }
    }
}
