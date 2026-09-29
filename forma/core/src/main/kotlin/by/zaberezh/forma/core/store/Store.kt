package by.zaberezh.forma.core.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; prettyPrint = false }

/**
 * Универсальная запись. Любой модуль (зал, еда, тело, готовка…) хранит данные
 * как entries своего `type` с JSON-телом — новые сферы не требуют миграций БД.
 */
data class Entry(
    val id: String,
    val type: String,
    val ts: Long,          // epoch millis
    val data: JsonElement,
) {
    val day: LocalDate get() = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(ts), ZONE)
}

val ZONE: ZoneId = ZoneId.of("Europe/Minsk")

fun newId(): String = UUID.randomUUID().toString()

/** Хранилище: записи + key-value (настройки, программа, состояние). */
interface Store {
    fun put(e: Entry)
    fun delete(id: String)
    fun get(id: String): Entry?
    fun list(type: String, fromTs: Long = Long.MIN_VALUE, toTs: Long = Long.MAX_VALUE): List<Entry> // по возрастанию ts
    fun kvGet(key: String): String?
    fun kvPut(key: String, value: String?)
    fun allTypes(): List<String>
}

/** Типизированный доступ к записям одного типа. */
class Kind<T>(val type: String, private val ser: KSerializer<T>) {
    fun decode(e: Entry): T = JSON.decodeFromJsonElement(ser, e.data)
    fun encode(v: T): JsonElement = JSON.encodeToJsonElement(ser, v)
    fun all(s: Store, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): List<Pair<Entry, T>> =
        s.list(type, from, to).mapNotNull { e -> runCatching { e to decode(e) }.getOrNull() }
    fun save(s: Store, v: T, ts: Long = System.currentTimeMillis(), id: String = newId()): Entry =
        Entry(id, type, ts, encode(v)).also(s::put)
}

/** Типизированное значение в KV. */
class Pref<T>(val key: String, private val ser: KSerializer<T>, private val default: () -> T) {
    fun get(s: Store): T = s.kvGet(key)?.let { runCatching { JSON.decodeFromString(ser, it) }.getOrNull() } ?: default()
    fun set(s: Store, v: T) = s.kvPut(key, JSON.encodeToString(ser, v))
}

class MemoryStore : Store {
    private val entries = LinkedHashMap<String, Entry>()
    private val kv = HashMap<String, String>()
    override fun put(e: Entry) { entries[e.id] = e }
    override fun delete(id: String) { entries.remove(id) }
    override fun get(id: String) = entries[id]
    override fun list(type: String, fromTs: Long, toTs: Long) =
        entries.values.filter { it.type == type && it.ts in fromTs..toTs }.sortedBy { it.ts }
    override fun kvGet(key: String) = kv[key]
    override fun kvPut(key: String, value: String?) { if (value == null) kv.remove(key) else kv[key] = value }
    override fun allTypes() = entries.values.map { it.type }.distinct()
}

fun LocalDate.startMs(): Long = atStartOfDay(ZONE).toInstant().toEpochMilli()
fun LocalDate.endMs(): Long = plusDays(1).startMs() - 1
fun today(): LocalDate = LocalDate.now(ZONE)
