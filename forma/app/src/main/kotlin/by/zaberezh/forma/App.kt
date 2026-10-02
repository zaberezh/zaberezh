package by.zaberezh.forma

import android.app.Application
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.migrateSettings
import by.zaberezh.forma.data.SqlStore
import by.zaberezh.forma.sys.Notify
import by.zaberezh.forma.sys.Reminders
import by.zaberezh.forma.sys.Secrets

object Forma {
    lateinit var store: SqlStore
    lateinit var app: android.content.Context
    fun ctx() = Ctx(store)
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        by.zaberezh.forma.sys.CrashLog.install(this)
        Forma.app = applicationContext
        Forma.store = SqlStore(this)
        migrateSettings(Forma.store)
        Secrets.migrate(this, Forma.store)   // ключ Claude — из базы в сейф Keystore
        Notify.channels(this)
        Reminders.scheduleAll(this)
    }
}
