package by.zaberezh.forma

import android.app.Application
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.migrateSettings
import by.zaberezh.forma.data.SqlStore
import by.zaberezh.forma.sys.Evening
import by.zaberezh.forma.sys.Morning
import by.zaberezh.forma.sys.Weigh
import by.zaberezh.forma.sys.Water
import by.zaberezh.forma.sys.Notify

object Forma {
    lateinit var store: SqlStore
    lateinit var app: android.content.Context
    fun ctx() = Ctx(store)
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Forma.app = applicationContext
        Forma.store = SqlStore(this)
        migrateSettings(Forma.store)
        by.zaberezh.forma.sys.Secrets.migrate(this, Forma.store)   // ключ Claude — из базы в сейф Keystore
        Notify.channels(this)
        Morning.schedule(this)
        Evening.schedule(this)
        Weigh.schedule(this)
        Water.schedule(this)
        by.zaberezh.forma.sys.WakeAlarm.schedule(this)
        by.zaberezh.forma.sys.IisCheck.schedule(this)
    }
}
