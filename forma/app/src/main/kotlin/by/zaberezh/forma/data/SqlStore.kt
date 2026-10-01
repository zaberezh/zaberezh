package by.zaberezh.forma.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.mutableIntStateOf
import by.zaberezh.forma.core.SETTINGS
import by.zaberezh.forma.core.store.Entry
import by.zaberezh.forma.core.store.JSON
import by.zaberezh.forma.core.store.Store
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** SQLite-реализация универсального хранилища: одна таблица записей + key-value. */
class SqlStore(context: Context) : SQLiteOpenHelper(context, "forma.db", null, 1), Store {
    // WAL: фоновые проверки (ИИС, напоминания) пишут, пока экран читает, — без блокировок и «database is locked»
    init { setWriteAheadLoggingEnabled(true) }

    /** Счётчик изменений — UI перечитывает данные при его смене. */
    val version = mutableIntStateOf(0)
    private fun changed() { version.intValue++ }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries(id TEXT PRIMARY KEY, type TEXT NOT NULL, ts INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE INDEX entries_type_ts ON entries(type, ts)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun put(e: Entry) {
        writableDatabase.insertWithOnConflict("entries", null, ContentValues().apply {
            put("id", e.id); put("type", e.type); put("ts", e.ts); put("data", e.data.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    override fun delete(id: String) {
        writableDatabase.delete("entries", "id=?", arrayOf(id)); changed()
    }

    private fun query(sql: String, args: Array<String>): List<Entry> =
        readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) add(Entry(c.getString(0), c.getString(1), c.getLong(2), JSON.parseToJsonElement(c.getString(3))))
            }
        }

    override fun get(id: String): Entry? = query("SELECT id,type,ts,data FROM entries WHERE id=?", arrayOf(id)).firstOrNull()

    override fun list(type: String, fromTs: Long, toTs: Long): List<Entry> = query(
        "SELECT id,type,ts,data FROM entries WHERE type=? AND ts BETWEEN CAST(? AS INTEGER) AND CAST(? AS INTEGER) ORDER BY ts",
        arrayOf(type, fromTs.toString(), toTs.toString()),
    )

    override fun kvGet(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun kvPut(key: String, value: String?) {
        if (value == null) writableDatabase.delete("kv", "k=?", arrayOf(key))
        else writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    override fun firstTs(): Long? =
        readableDatabase.rawQuery("SELECT MIN(ts) FROM entries", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    override fun allTypes(): List<String> =
        readableDatabase.rawQuery("SELECT DISTINCT type FROM entries", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Полный экспорт (без API-ключа). Фото — только пути. */
    fun exportJson(): String {
        val entries = query("SELECT id,type,ts,data FROM entries ORDER BY ts", emptyArray()).map {
            JsonObject(mapOf("id" to JsonPrimitive(it.id), "type" to JsonPrimitive(it.type), "ts" to JsonPrimitive(it.ts), "data" to it.data))
        }
        val kv = readableDatabase.rawQuery("SELECT k,v FROM kv", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), JsonPrimitive(c.getString(1))) }
        }.toMutableMap()
        kv[SETTINGS.key] = JsonPrimitive(JSON.encodeToString(by.zaberezh.forma.core.Settings.serializer(), SETTINGS.get(this).copy(apiKey = "")))
        return JsonObject(mapOf("format" to JsonPrimitive("forma-1"), "entries" to JsonArray(entries), "kv" to JsonObject(kv))).toString()
    }

    /** Импорт поверх текущих данных (записи с тем же id заменяются). Ключ Claude живёт в сейфе — импорт его не трогает. */
    fun importJson(text: String) {
        val root = JSON.parseToJsonElement(text).jsonObject
        val db = writableDatabase
        db.beginTransaction()
        try {
            root["entries"]?.jsonArray?.forEach { el ->
                val o = el.jsonObject
                db.insertWithOnConflict("entries", null, ContentValues().apply {
                    put("id", o["id"]!!.jsonPrimitive.content); put("type", o["type"]!!.jsonPrimitive.content)
                    put("ts", o["ts"]!!.jsonPrimitive.long); put("data", o["data"].toString())
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            root["kv"]?.jsonObject?.forEach { (k, v) ->
                db.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v.jsonPrimitive.content) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }
}
