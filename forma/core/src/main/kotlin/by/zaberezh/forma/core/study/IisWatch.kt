package by.zaberezh.forma.core.study

import by.zaberezh.forma.core.store.Store

/**
 * Фоновая проверка ИИС: снимок отметок и пропусков из «Успеваемости»; новое по сравнению с прошлым снимком —
 * в уведомление. Первый снимок ничего не присылает (иначе пришли бы все старые отметки разом).
 */
object IisWatch {
    private const val KEY = "iis.snapshot"

    data class News(val marks: List<String>, val omissions: List<String>) {
        val empty get() = marks.isEmpty() && omissions.isEmpty()
    }

    /** Ключи: отметка — предмет, тип, дата, порядковый номер на занятии, значение; пропуск — предмет, тип, дата, часы. */
    fun snapshot(r: Rating): Map<String, String> = buildMap {
        for (s in r.subjects) for (t in s.types) for (l in t.lessons) {
            l.marks.forEachIndexed { i, m ->
                put("M|${s.abbrev}|${t.abbrev}|${l.date}|$i|${m.mark}",
                    "${s.name} · ${t.abbrev} ${l.date.take(5)} — ${m.mark}" + (m.task?.let { " (ЛР $it)" } ?: ""))
            }
            if (l.missed > 0) put("O|${s.abbrev}|${t.abbrev}|${l.date}|${l.missed}", "${s.name} · ${t.abbrev} ${l.date.take(5)} — ${l.missed} ч")
        }
    }

    /** Сравнить со снимком в хранилище и сохранить новый. */
    fun check(s: Store, r: Rating): News {
        val now = snapshot(r)
        val old = s.kvGet(KEY)?.split('\n')?.toSet()
        s.kvPut(KEY, now.keys.joinToString("\n"))
        if (old == null) return News(emptyList(), emptyList())
        val fresh = now.filterKeys { it !in old }
        return News(fresh.filterKeys { it.startsWith("M|") }.values.toList(), fresh.filterKeys { it.startsWith("O|") }.values.toList())
    }

    fun forget(s: Store) = s.kvPut(KEY, null)
}
