package by.zaberezh.forma.core

import by.zaberezh.forma.core.food.Menus
import by.zaberezh.forma.core.food.splitParts
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MenuTest {
    private fun one(text: String) = Menus.resolve(text).also { assertEquals(emptyList(), it.rest, "остаток для «$text»") }.items.single()

    @Test fun menuLoadsAllRowsWithSaneNumbers() {
        val kfc = Menus.all.first()
        assertTrue(kfc.items.size > 150, "позиций: ${kfc.items.size}")
        kfc.items.filter { it.per100.kcal > 20 }.forEach { m ->
            val calc = m.per100.p * 4 + m.per100.f * 9 + m.per100.c * 4
            assertTrue(abs(calc - m.per100.kcal) / m.per100.kcal < 0.7, "${m.name}: $calc vs ${m.per100.kcal}")
        }
    }

    @Test fun picksDefaultAndExplicitVariants() {
        assertEquals("KFC · Твистер оригинальный", one("кфс твистер").name)
        assertEquals(184.0, one("кфс твистер").grams)
        assertEquals("KFC · Твистер острый", one("твистер острый").name)                     // «твистер» сам включает меню
        assertEquals("KFC · Твистер де люкс острый", one("твистер делюкс острый").name)
        assertEquals("KFC · Зингер бургер", one("зингер").name)
        assertEquals("KFC · Зингер бургер смарт", one("зингер смарт").name)
        assertEquals("KFC · Тост с сыром и беконом", one("кфс тост с сыром и беконом").name)
    }

    @Test fun sizesAndCounts() {
        assertEquals(500.0, one("кфс кола 0,5").grams)
        assertEquals("KFC · Кока-кола без сахара 0,4", one("кфс кола без сахара").name)
        assertEquals(117.0, one("кфс 9 наггетсов").grams)
        assertEquals(156.0, one("кфс наггетсы 12 шт").grams)               // нет такой порции — 13 г за штуку
        assertEquals(135.0, one("кфс крылья 5").grams)
        assertEquals(368.0, one("2 твистера кфс").grams)
        assertEquals(200.0, one("кфс картошка фри большая").grams)
        assertEquals(60.0, one("кфс фри малая").grams)
        assertEquals(24.0, one("кфс соус сырный").grams)
        assertEquals(150.0, one("кфс фри 150г").grams)
    }

    @Test fun mixedMealLeavesForeignPartsForOtherSteps() {
        val r = Menus.resolve("кфс: твистер острый, фри, кола 0,5, гречка 200г")
        assertEquals(listOf("KFC · Твистер острый", "KFC · Картофель фри средний", "KFC · Кока-кола 0,5"), r.items.map { it.name })
        assertEquals(listOf("гречка 200г"), r.rest)
        val k = r.items.sumOf { it.total.kcal }
        assertTrue(abs(k - (178 * 2.40 + 100 * 2.91 + 500 * 0.425)) < 1, "ккал $k")
    }

    @Test fun kinzaRestaurant() {
        val r = Menus.resolve("донер из кинзы, лагман кинза")
        assertEquals(listOf("Кинза · Донер классический", "Кинза · Лагман курица овощи"), r.items.map { it.name })
        assertEquals(189.0, r.items[0].per100.kcal); assertEquals(162.3, r.items[1].per100.kcal)
        assertEquals(335.0, r.items[0].grams)
        assertEquals(410.0, one("большой донер кинза").grams)
        assertEquals(250.0, one("кинза донер 250г").grams)
        assertTrue(Menus.resolve("донер").items.isEmpty())   // без «кинза» — не их донер
    }

    @Test fun makPerPortionValues() {
        val r = Menus.resolve("мак: вишневый пирожок, шейк шоколадный, 2 кукиса шоколадных, сырный соус")
        assertEquals(listOf("Мак · Вишневый пирожок", "Мак · Молочный коктейль шоколадный средний 0,4",
            "Мак · Шоколадный кукис", "Мак · Соус Сырный"), r.items.map { it.name })
        val kcal = r.items.map { Math.round(it.total.kcal).toInt() }
        assertEquals(listOf(249, 337, 390, 75), kcal)                    // на порцию — как в приложении, ×2 кукиса
        assertEquals(5.5, one("макдак ванильный коктейль").total.f, 0.01)
        assertTrue(Menus.resolve("вишневый пирожок").items.isEmpty())   // без «мак» — не их
        assertEquals("Мак · Мак Бургер", one("мак бургер").name)
        assertEquals("Мак · Мак Бургер", one("бигмак из мака").name)
        assertEquals("Мак · Чизбургер", one("чизбургер мак").name)
        assertEquals("Мак · Двойной Чизбургер", one("мак двойной чизбургер").name)
        assertEquals("Мак · Фри средняя порция", one("мак фри").name)
        assertEquals("Мак · Фри большая порция", one("мак картошка фри большая").name)
        assertEquals(151.0, one("мак 10 наггетсов").grams)
        assertEquals("Мак · Сыр фри", one("мак сыр фри").name)
        assertEquals("Мак · Чикен Классик", one("мак чикен").name)
        assertEquals("Мак · Мехико Чикен", one("мехико чикен из мака").name)
        assertEquals("Мак · Цезарь Ролл", one("мак цезарь ролл").name)
        assertEquals(327.0, Math.round(one("мак чикенбургер").total.kcal).toDouble())
        assertEquals(434.0, Math.round(one("мак стрипсы 5").total.kcal).toDouble())
    }

    @Test fun ignoredWithoutChainOrSignature() {
        val r = Menus.resolve("кола 0,5, фри, гречка")
        assertTrue(r.items.isEmpty())
        assertEquals(listOf("кола 0,5", "фри", "гречка"), r.rest)
        assertEquals(listOf("кола 0,5", "яйца 2"), splitParts("кола 0,5, яйца 2"))
    }
}
