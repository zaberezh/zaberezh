package by.zaberezh.forma.core

import by.zaberezh.forma.core.food.Menus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Меню Burger King Беларусь (PDF «Сведения о продукции», 11.10.2024). */
class BurgerKingTest {
    private fun one(q: String) = Menus.resolve(q).items.also { assertEquals(1, it.size, "$q → $it") }.single()

    @Test fun whopperAndSizes() {
        val w = one("воппер бк")
        assertEquals("BK · Воппер", w.name); assertEquals(264.0, w.grams); assertEquals(528.0, w.total.kcal, 1.0)
        assertEquals("BK · Воппер с сыром Двойной", one("двойной воппер с сыром").name)
        assertEquals("BK · Воппер", one("бургер из бургер кинга").name)            // «бургер кинг» — название сети, а не слова позиции
        assertEquals("BK · Кинг Фри стандартный", one("кинг фри").name)
        val big = one("большая картошка фри из бургер кинга")
        assertEquals("BK · Кинг Фри большой", big.name); assertEquals(126.0, big.grams)
        assertEquals(87.0, one("6 наггетсов бк").grams)
        assertEquals(500.0, one("пепси 0,5 бк").grams)
    }

    @Test fun allRowsAreConsistent() {
        val bk = Menus.all.first { it.prefix == "BK" }
        assertTrue(bk.items.size > 150, "${bk.items.size}")
        bk.items.forEach { i ->
            val m = i.per100
            val calc = m.p * 4 + m.f * 9 + m.c * 4
            if (calc > 20) assertTrue(kotlin.math.abs(calc - m.kcal) / calc < 0.25, "${i.name}: ${m.kcal} ккал при Б/Ж/У → $calc")
            assertTrue(i.grams > 0, i.name)
        }
    }
}
