package by.zaberezh.forma.core

import by.zaberezh.forma.core.food.FoodItem
import by.zaberezh.forma.core.food.FoodPipeline
import by.zaberezh.forma.core.food.Macro
import by.zaberezh.forma.core.food.Menus
import by.zaberezh.forma.core.food.WebFood
import by.zaberezh.forma.core.store.MemoryStore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoodSearchTest {
    private fun basic(text: String) = Menus.basic.resolve(text).also { assertEquals(emptyList(), it.rest, "остаток для «$text»") }.items

    @Test fun commonFoodsOfflineWithCountsInWords() {
        assertEquals(listOf("Банан" to 120.0), basic("один банан").map { it.name to it.grams })
        assertEquals(60.0, basic("пол банана").single().grams)
        assertEquals(60.0, basic("полбанана").single().grams)
        assertEquals(156.0, basic("большой банан").single().grams)
        assertEquals(110.0, basic("2 яйца").single().grams)
        assertEquals(110.0, basic("два яйца").single().grams)
        assertEquals(200.0, basic("гречка 200г").single().grams)
        assertEquals("Гречка вареная", basic("гречка").single().name)
        assertEquals("Гречка сухая", basic("гречка сухая 70г").single().name)
        assertEquals("Яблоко", basic("зеленое яблоко").single().name)
        assertEquals("Куриная грудка вареная", basic("курица 150г").single().name)
        assertEquals(500.0, basic("кола 0,5").single().grams)
        assertEquals("Молоко 3,2%", basic("молоко 3,2% 200 мл").single().name)
        assertEquals(200.0, basic("молоко 3,2% 200 мл").single().grams)
        assertEquals(400.0, basic("2 творога").single().grams)                  // жирность 5% — не «5 штук»
        assertEquals(60.0, basic("2 куска хлеба").single().grams)
        val kcal = basic("один банан, 2 яйца, гречка 200г").sumOf { it.total.kcal }
        assertTrue(abs(kcal - (115.2 + 172.7 + 220)) < 1, "ккал $kcal")
    }

    @Test fun brandedAndRestaurantFoodIsNotGuessedFromTable() {
        assertEquals(listOf("теос про клубника"), Menus.basic.resolve("теос про клубника").rest)
        assertEquals(listOf("шаурма большая в Шаурма Шеф"), Menus.basic.resolve("шаурма большая в Шаурма Шеф").rest)
        assertEquals(listOf("молоко Савушкин"), Menus.basic.resolve("молоко Савушкин").rest)
    }

    @Test fun parsesMacrosFromSearchSnippetsAndSites() {
        val serp = """<div class="result">Банан — калорийность 96 ккал на 100 г. Белки 1,5 г, жиры 0,2 г, углеводы 21,8 г.</div>
            <a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fcalorizator.ru%2Fproduct%2Ffruit%2Fbanana&rut=1">calorizator</a>
            <div>Сникерс: Б/Ж/У 8.4/27.1/60.3, 497 ккал</div>"""
        val page = "<h1>Калорийность Банан</h1><table><tr><td>Калорийность</td><td>96 кКал</td><td>1684 кКал</td></tr>" +
            "<tr><td>Белки</td><td>1.5 г</td><td>76 г</td></tr><tr><td>Жиры</td><td>0.2 г</td><td>56 г</td></tr>" +
            "<tr><td>Углеводы</td><td>21.8 г</td><td>219 г</td></tr></table>"
        val web = WebFood(fetch = { url -> when { "duckduckgo" in url -> serp; "calorizator.ru" in url -> page; else -> null } })
        val f = assertNotNull(web.lookup("банан"))
        assertEquals(96.0, f.per100.kcal); assertEquals(1.5, f.per100.p); assertEquals("calorizator.ru", f.from)
        assertEquals(Macro(497.0, 8.4, 27.1, 60.3), WebFood.parse("Сникерс: Б/Ж/У 8.4/27.1/60.3, 497 ккал"))
        assertEquals(Macro(52.0, 0.3, 0.2, 14.0), WebFood.parse("Яблоко 52 ккал Б: 0.3 Ж: 0.2 У: 14"))
        assertNull(WebFood.parse("всего 3 ккал на порцию, белки 40, жиры 50, углеводы 60"))  // не сходится — мусор
        val item = assertNotNull(web.find("2 банана 240г"))
        assertEquals(240.0, item.grams)
        assertEquals("банан", WebFood.nameOf("один банан 120 г"))
    }

    @Test fun pipelineIsOfflineFirstAndKeepsResultsWhenClaudeFails() {
        val s = MemoryStore()
        var claudeCalled = false
        val r = FoodPipeline(s, fetch = { null }, claude = { _, _ -> claudeCalled = true; emptyList() }).run("один банан, 2 яйца")
        assertEquals(listOf("Банан", "Яйцо куриное"), r.items.map { it.name })
        assertEquals("2 таблица", r.how)
        assertTrue(!claudeCalled, "Claude не нужен для банана")

        val fail = FoodPipeline(s, fetch = { null }, claude = { _, _ -> error("провайдер упал") })
            .run("кфс твистер, банан, бургер из Бургер Кинга")
        assertEquals(listOf("KFC · Твистер оригинальный", "Банан"), fail.items.map { it.name })
        assertEquals(listOf("бургер из Бургер Кинга"), fail.missing)
        assertNotNull(fail.error)

        val claude = FoodPipeline(s, fetch = { null }, claude = { rest, _ -> listOf(FoodItem(rest, 200.0, Macro(250.0, 12.0, 12.0, 25.0))) })
            .run("банан и бургер из Бургер Кинга")
        assertEquals("1 таблица · 1 Claude", claude.how)
    }

    @Test fun networkBudgetStopsSlowSearch() {
        var now = 0L
        var calls = 0
        val r = FoodPipeline(MemoryStore(), fetch = { calls++; now += 10_000; null }, netBudgetMs = 25_000, clock = { now }).run("сникерс")
        assertTrue(calls <= 4, "запросов $calls")
        assertEquals(listOf("сникерс"), r.missing)
    }

    @Test fun everydayDishesAndWithCombos() {
        fun names(q: String) = FoodPipeline(MemoryStore(), fetch = { null }).run(q).also { assertTrue(it.missing.isEmpty(), "не найдено: $q") }.items
        assertEquals("Суп гороховый", names("гороховый суп").single().name)
        assertEquals(listOf("Макароны вареные", "Котлета"), names("макароны с 2 котлетами").map { it.name })
        assertEquals(160.0, names("макароны с 2 котлетами")[1].grams)                 // две котлеты по 80 г
        assertEquals(2, names("гречка с курицей").size)
        assertEquals(400.0, names("солянка 400г").single().grams)
    }

    @Test fun statedGramsWinOverOutsideEstimate() {
        val est = listOf(FoodItem("Шоколадка Аленка", 100.0, Macro(550.0, 7.0, 35.0, 54.0)), FoodItem("Кола", 330.0, Macro(42.0, 0.0, 0.0, 10.6)))
        val fixed = by.zaberezh.forma.core.food.keepGrams(listOf("шоколадка аленка 30г", "кола"), est)
        assertEquals(listOf(30.0, 330.0), fixed.map { it.grams })
        assertEquals(550.0, fixed[0].per100.kcal)                                      // на 100 г — те же
        val claude = FoodPipeline(MemoryStore(), fetch = { null }, claude = { _, _ -> listOf(FoodItem("Шоколадка Милка", 90.0, Macro(530.0, 6.0, 30.0, 58.0))) })
            .run("шоколадка милка с орехами 30 г")
        assertEquals(30.0, claude.items.single().grams)
    }
}
