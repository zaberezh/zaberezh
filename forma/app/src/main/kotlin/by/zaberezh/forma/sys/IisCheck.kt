package by.zaberezh.forma.sys

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
 * Фоновая проверка кабинета ИИС раз в ~3 часа (неточный будильник — бережёт батарею):
 * новые отметки и пропуски → уведомления; лабы из ИИС → вкладка «Лабы» (новые и засчитанные).
 * Работает, пока жива сессия ИИС; закончилась — одно уведомление «войди снова».
 */
object IisCheck {
    private const val EXPIRED = "iis.expired.notified"

    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 20, Intent(c, IisCheckReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val on = SETTINGS.get(Forma.store).iisWatch && SecureStore.get(c, "cookie") != null
        if (!on) { am.cancel(pi(c)); return }
        val every = 3 * AlarmManager.INTERVAL_HOUR
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + every, every, pi(c))
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

class IisCheckReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val done = goAsync()
        Thread {
            try { runCatching { IisCheck.run(c.applicationContext) } } finally { done.finish() }
        }.start()
    }
}
