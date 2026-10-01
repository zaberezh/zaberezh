package by.zaberezh.forma.sys

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import by.zaberezh.forma.Forma
import by.zaberezh.forma.core.SETTINGS
import by.zaberezh.forma.core.study.Cabinet
import by.zaberezh.forma.core.study.Iis
import by.zaberezh.forma.core.study.IisSession
import by.zaberezh.forma.core.study.IisUnauthorized
import by.zaberezh.forma.core.study.IisWatch
import by.zaberezh.forma.core.study.Rating
import by.zaberezh.forma.core.study.Study

/**
 * Фоновая проверка кабинета ИИС раз в ~3 часа: новые отметки и пропуски → уведомления;
 * лабы из ИИС → вкладка «Лабы» (новые и засчитанные). Работает, пока жива сессия ИИС; закончилась — одно
 * уведомление «войди снова». Задача JobScheduler: запускается только при наличии сети, система сама
 * выбирает момент (бережёт батарею) и даёт на запрос до 10 минут — медленный ИИС не обрывается.
 */
object IisCheck {
    private const val EXPIRED = "iis.expired.notified"
    private const val JOB = 20
    private const val EVERY = 3 * 60 * 60_000L

    /** Прежняя версия проверяла по будильнику — снимаем его (класс приёмника уже удалён, ищем по имени). */
    private fun cancelLegacyAlarm(c: Context) = runCatching {
        val old = Intent().setClassName(c, "by.zaberezh.forma.sys.IisCheckReceiver")
        c.getSystemService(AlarmManager::class.java).cancel(
            PendingIntent.getBroadcast(c, 20, old, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    fun schedule(c: Context) {
        cancelLegacyAlarm(c)
        val js = c.getSystemService(JobScheduler::class.java)
        val on = SETTINGS.get(Forma.store).iisWatch && SecureStore.get(c, "cookie") != null
        if (!on) { js.cancel(JOB); return }
        if (js.getPendingJob(JOB) != null) return   // уже стоит — не сбрасываем отсчёт при каждом запуске приложения
        js.schedule(JobInfo.Builder(JOB, ComponentName(c, IisCheckJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(EVERY)
            .setPersisted(true)   // переживает перезагрузку
            .build())
    }

    /** После входа: снова можно сообщить об истёкшей сессии. */
    fun loggedIn(c: Context) { Forma.store.kvPut(EXPIRED, null); schedule(c) }

    /** То же, что делает приложение при загрузке «Успеваемости»: лабы из ИИС и снимок отметок. */
    fun apply(r: Rating) = Study.syncFromIis(Forma.store, r)

    fun run(c: Context) {
        val s = Forma.store
        val cookie = SecureStore.get(c, "cookie") ?: return
        val session = IisSession(cookie, SecureStore.get(c, "profile").orEmpty())
        val r = try {
            Cabinet.load(Iis.jdk, session, "rating") as Rating
        } catch (e: IisUnauthorized) {
            if (s.kvGet(EXPIRED) == null) {
                Notify.post(c, 23, "Сессия ИИС закончилась", listOf("Войди в «Учёба → Кабинет», чтобы снова получать отметки и пропуски."))
                s.kvPut(EXPIRED, "1")
            }
            return
        }
        val sync = Study.syncFromIis(s, r)
        val news = IisWatch.check(s, r)
        if (news.marks.isNotEmpty()) Notify.post(c, 20, if (news.marks.size == 1) "Новая отметка" else "Новые отметки: ${news.marks.size}", news.marks, private = true)
        if (news.omissions.isNotEmpty()) Notify.post(c, 21, "Записан пропуск", news.omissions, private = true)
        val labs = sync.graded.map { "Засчитана: $it" } + sync.created.takeIf { it.isNotEmpty() }?.let { listOf("Новые в ИИС: " + it.joinToString(", ")) }.orEmpty()
        if (labs.isNotEmpty()) Notify.post(c, 22, "Лабы", labs, private = true)
    }
}

class IisCheckJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread {
            try { runCatching { IisCheck.run(applicationContext) } } finally { jobFinished(p, false) }
        }.start()
        return true   // работа идёт в своём потоке
    }

    override fun onStopJob(p: JobParameters) = false   // система прервала (пропала сеть) — подождём следующего раза
}
