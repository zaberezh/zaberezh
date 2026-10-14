package by.zaberezh.forma.core

import by.zaberezh.forma.core.store.Entry
import by.zaberezh.forma.core.store.EntryCache
import by.zaberezh.forma.core.store.Kind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Кэш хранилища: база читается один раз, запись обновляет кэш точечно, гонка не оставляет старых данных. */
class EntryCacheTest {
    private fun e(id: String, ts: Long, type: String = "t") = Entry(id, type, ts, JsonPrimitive(id))

    @Test fun readsOnceAndRanges() {
        val c = EntryCache()
        var loads = 0
        val db = listOf(e("a", 10), e("b", 20), e("c", 20), e("d", 30))
        fun list(from: Long, to: Long) = c.list("t", from, to) { loads++; db }.map { it.id }
        assertEquals(listOf("a", "b", "c", "d"), list(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(listOf("b", "c"), list(20, 20))           // обе границы включительно
        assertEquals(listOf("b", "c", "d"), list(11, 99))
        assertEquals(emptyList(), list(31, 40))
        assertEquals(emptyList(), list(0, 9))
        assertEquals(1, loads)
    }

    @Test fun writesUpdateInPlace() {
        val c = EntryCache()
        c.list("t", Long.MIN_VALUE, Long.MAX_VALUE) { listOf(e("a", 10), e("b", 20)) }
        c.put(e("x", 15)); c.put(e("a", 25))                  // новая — по месту во времени; замена по id — переезжает
        val fail = { error("в базу ходить не нужно") }
        assertEquals(listOf("x", "b", "a"), c.list("t", Long.MIN_VALUE, Long.MAX_VALUE, fail).map { it.id })
        c.deleted("b", e("b", 20))
        assertEquals(listOf("x", "a"), c.list("t", Long.MIN_VALUE, Long.MAX_VALUE, fail).map { it.id })
        assertNull(c.get("b", fail))
        assertEquals("x", c.get("x", fail)!!.id)
        c.kvPut("k", "v"); assertEquals("v", c.kv("k", fail))
        c.kvPut("k", null); assertNull(c.kv("k", fail))
    }

    @Test fun readRacingWithWriteIsNotCached() {
        val c = EntryCache()
        // пока читали из базы, другой поток записал — прочитанное устарело и в кэш не попадает
        val stale = c.list("t", Long.MIN_VALUE, Long.MAX_VALUE) { c.put(e("new", 5)); listOf(e("a", 10)) }
        assertEquals(listOf("a"), stale.map { it.id })
        var loads = 0
        c.list("t", Long.MIN_VALUE, Long.MAX_VALUE) { loads++; listOf(e("new", 5), e("a", 10)) }
        assertEquals(1, loads)
    }

    @Serializable data class V(val n: Int)

    @Test fun decodedOnce() {
        val k = Kind("v", V.serializer())
        val x = k.save(by.zaberezh.forma.core.store.MemoryStore(), V(3))
        assertSame(k.decode(x), k.decode(x))                  // одна запись — один разбор
        assertTrue(k.decode(x).n == 3)
    }
}
