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
    val day: LocalDate by lazy(LazyThreadSafetyMode.PUBLICATION) { LocalDate.ofInstant(java.time.Instant.ofEpochMilli(ts), ZONE) }

    /** Разобранное значение ([Kind.decode]): одна и та же запись не разбирается заново на каждый кадр. Не часть equals. */
    @Volatile internal var memo: Pair<Kind<*>, Any?>? = null
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
    /** Время самой ранней записи (для «с какого дня есть данные») — без загрузки всех записей. */
    fun firstTs(): Long? = allTypes().mapNotNull { list(it).firstOrNull()?.ts }.minOrNull()
}

/**
 * Значение, посчитанное от списка: пока хранилище отдаёт тот же самый список (ничего не менялось — см. EntryCache),
 * не пересчитывается. Сравнение по ссылке: изменился список — новый объект — посчитаем заново.
 */
class Memo<K : Any, V>(private val f: (K) -> V) {
    @Volatile private var last: Pair<K, V>? = null
    operator fun invoke(k: K): V {
        last?.let { (kk, v) -> if (kk === k) return v }
        return f(k).also { last = k to it }
    }
}

/** Типизированный доступ к записям одного типа. */
class Kind<T>(val type: String, private val ser: KSerializer<T>) {
    @Suppress("UNCHECKED_CAST")
    fun decode(e: Entry): T {
        e.memo?.let { (k, v) -> if (k === this) return v as T }
        return JSON.decodeFromJsonElement(ser, e.data).also { e.memo = this to it }
    }
    fun encode(v: T): JsonElement = JSON.encodeToJsonElement(ser, v)
    // хранилище с кэшем отдаёт тот же список, пока ничего не менялось, — и разобранный список тот же
    private val allMemo = Memo { l: List<Entry> -> l.mapNotNull { e -> runCatching { e to decode(e) }.getOrNull() } }
    fun all(s: Store, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): List<Pair<Entry, T>> =
        s.list(type, from, to).let { l -> if (from == Long.MIN_VALUE && to == Long.MAX_VALUE) allMemo(l) else l.mapNotNull { e -> runCatching { e to decode(e) }.getOrNull() } }
    fun save(s: Store, v: T, ts: Long = System.currentTimeMillis(), id: String = newId()): Entry =
        Entry(id, type, ts, encode(v)).also(s::put)
}

/** Типизированное значение в KV. */
class Pref<T>(val key: String, private val ser: KSerializer<T>, private val default: () -> T) {
    // последний разобранный текст → значение: настройки и программа читаются десятки раз за кадр, а меняются редко
    @Volatile private var memo: Pair<String, T>? = null
    fun get(s: Store): T {
        val raw = s.kvGet(key) ?: return default()
        memo?.let { (r, v) -> if (r === raw || r == raw) return v }
        val v = runCatching { JSON.decodeFromString(ser, raw) }.getOrNull() ?: return default()
        memo = raw to v
        return v
    }
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
