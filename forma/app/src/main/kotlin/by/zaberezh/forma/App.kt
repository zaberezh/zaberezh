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
    fun ctx() = Ctx(store)
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Forma.store = SqlStore(this)
        migrateSettings(Forma.store)
        Notify.channels(this)
        Morning.schedule(this)
        Evening.schedule(this)
        Weigh.schedule(this)
        Water.schedule(this)
    }
}
