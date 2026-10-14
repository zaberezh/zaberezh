package by.zaberezh.forma.core.store

import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Кэш хранилища в памяти. Экраны перечитывают одно и то же при каждом изменении (настройки, программа, тренировки,
 * еда) — без кэша это каждый раз SQL и разбор JSON всех записей. Хранит записи по типу (по возрастанию ts), отдельные
 * id и key-value; запись в базу обновляет кэш точечно, без перечитывания.
 * Потокобезопасен (фоновые проверки пишут, пока экран читает): gen — счётчик записей; прочитанное из базы до чужой
 * записи в кэш не кладётся, иначе там навсегда застряли бы старые данные.
 */
class EntryCache {
    private val gen = AtomicLong()
    private val byType = ConcurrentHashMap<String, List<Entry>>()
    private val byId = ConcurrentHashMap<String, Optional<Entry>>()
    private val kv = ConcurrentHashMap<String, Optional<String>>()

    private fun <V : Any> cached(map: ConcurrentHashMap<String, V>, key: String, load: () -> V): V {
        map[key]?.let { return it }
        val g = gen.get()
        val v = load()
        if (gen.get() == g) map.putIfAbsent(key, v)
        return v
    }

    /** Записи типа в диапазоне [from]..[to]; [load] — все записи типа из базы по возрастанию ts. */
    fun list(type: String, from: Long, to: Long, load: () -> List<Entry>): List<Entry> {
        val all = cached(byType, type, load)
        if (from == Long.MIN_VALUE && to == Long.MAX_VALUE) return all
        // первый индекс с ts >= t (after — с ts > t): диапазон двоичным поиском, а не фильтром по всем записям
        fun first(t: Long, after: Boolean): Int {
            var lo = 0; var hi = all.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (all[mid].ts < t || after && all[mid].ts == t) lo = mid + 1 else hi = mid }
            return lo
        }
        val i = first(from, false); val j = first(to, true)
        return if (i >= j) emptyList() else all.subList(i, j)
    }

    fun get(id: String, load: () -> Entry?): Entry? = cached(byId, id) { Optional.ofNullable(load()) }.orElse(null)

    fun kv(key: String, load: () -> String?): String? = cached(kv, key) { Optional.ofNullable(load()) }.orElse(null)

    /** Запись сохранена в базе (новая или замена по id). */
    fun put(e: Entry) {
        gen.incrementAndGet()
        val old = byId[e.id]?.orElse(null)
        if (old != null && old.type != e.type) byType.remove(old.type)
        byType.computeIfPresent(e.type) { _, l -> (l.filter { it.id != e.id } + e).sortedBy { it.ts } }
        byId[e.id] = Optional.of(e)
    }

    /** Запись удалена; [old] — какой она была (null — неизвестно: сбрасываем списки целиком). */
    fun deleted(id: String, old: Entry?) {
        gen.incrementAndGet()
        if (old != null) byType.computeIfPresent(old.type) { _, l -> l.filter { it.id != id } } else byType.clear()
        byId[id] = Optional.empty()
    }

    fun kvPut(key: String, value: String?) { gen.incrementAndGet(); kv[key] = Optional.ofNullable(value) }

    /** База изменена в обход (импорт) — забыть всё. */
    fun clear() { gen.incrementAndGet(); byType.clear(); byId.clear(); kv.clear() }
}
