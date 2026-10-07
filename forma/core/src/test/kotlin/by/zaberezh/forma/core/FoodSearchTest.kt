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

    @Test fun fiberFromLibrary() {
        assertEquals(3.12, basic("один банан").single().total.fib, 0.01)                 // 120 г × 2.6 г/100 г
        assertEquals(0.0, basic("200г куриная грудка вареная").single().total.fib)        // мясо — без клетчатки
        val names = Menus.basic.items.map { it.name }.toSet()
        val fiber = javaClass.getResource("/menus/fiber.txt")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBefore('|').trim() }
        assertEquals(emptyList(), fiber.filter { it !in names }, "в fiber.txt названия, которых нет в basic.txt")
    }

    @Test fun everydayFoodsAndWordForms() {
        fun names(q: String) = basic(q).map { it.name to it.grams }
        assertEquals(listOf("Макароны вареные" to 300.0, "Индейка копченая" to 50.0), names("макароны 300г и копченое мясо индейки"))
        assertEquals(listOf("Сыр твердый" to 50.0), names("сыра 50 г"))                 // «сыра» — сыр, а не сырник
        assertEquals(listOf("Сыр твердый" to 60.0), names("2 куска сыра"))
        assertEquals("Сыр плавленый", names("плавленый сыр").single().first)
        assertEquals("Сырник", names("сырник").single().first)
        assertEquals("Индейка вареная", names("мясо индейки").single().first)
        assertEquals("Макароны вареные", names("паста 200г").single().first)            // не арахисовая паста
        assertEquals("Макароны вареные", names("спагетти").single().first)              // не болоньезе
        assertEquals("Арахисовая паста", names("арахисовая паста 30г").single().first)
        assertEquals("Хлеб белый", names("2 куска хлеба").single().first)
        assertEquals("Ветчина", names("ветчина").single().first)
    }

    @Test fun networkFailureKeepsTableResults() {
        val r = FoodPipeline(MemoryStore(), fetch = { error("нет сети") }).run("макароны 300г, плавленый сыр, суши филадельфия")
        assertEquals(listOf("Макароны вареные", "Сыр плавленый"), r.items.map { it.name })
        assertEquals(listOf("суши филадельфия"), r.missing)
    }

    @Test fun openFoodFactsThenCalorizatorSearch() {
        val off = """{"count":3,"products":[
            {"product_name":"Грудка индейки копчёная","nutriments":{"energy-kcal_100g":110,"proteins_100g":20,"fat_100g":3,"carbohydrates_100g":0.5}},
            {"product_name_ru":"Индейка копчено-вареная","nutriments":{"energy-kcal_100g":130,"proteins_100g":19,"fat_100g":6,"carbohydrates_100g":0.4,"fiber_100g":0}},
            {"product_name":"Сок яблочный","nutriments":{"energy-kcal_100g":46,"proteins_100g":0.1,"fat_100g":0.1,"carbohydrates_100g":11}}]}"""
        val viaOff = WebFood(fetch = { url -> if ("openfoodfacts" in url) off else null }).lookup("индейка копченая")
        assertEquals("Open Food Facts", viaOff?.from)
        assertEquals(130.0, viaOff?.per100?.kcal)                      // медиана по двум индейкам, сок отброшен по названию

        val search = """<ol class="search-results"><li><a href="https://calorizator.ru/product/meat/turkey-1">Индейка</a></li></ol>"""
        val page = "<table><tr><td>Калорийность</td><td>276 кКал</td></tr><tr><td>Белки</td><td>19.5 г</td></tr>" +
            "<tr><td>Жиры</td><td>22 г</td></tr><tr><td>Углеводы</td><td>0 г</td></tr></table>"
        val viaSite = WebFood(fetch = { url -> when { "/search/node/" in url -> search; "/product/meat/turkey-1" in url -> page; else -> null } }).lookup("индейка")
        assertEquals("calorizator.ru", viaSite?.from)
        assertEquals(19.5, viaSite?.per100?.p)
    }

    @Test fun sosediLabelFirst() {
        // ответы их API — как пришли с сайта (кефир: калории в кДж)
        val search = """{"took":9,"data":[{"id":300376668,"name":"4810319008408 молоко детское депи 3.2% 250мл","description":""},
            {"id":1318,"name":"4810268020827 кефир 1,5% 0,95л","description":""}]}"""
        val card = """{"id":1318,"name":"Кефир 1,5% 0,95л","calorie":"174.5","fat":"1.5","protein":"3","carbohydrate":"4"}"""
        val web = WebFood(fetch = { url -> when { "/v2/products/search" in url -> search; url.endsWith("/products/1318/10") -> card; else -> null } })
        val f = web.lookup("кефир")
        assertEquals("sosedi-dostavka.by · кефир 1,5% 0,95л", f?.from)
        assertEquals(41.7, kotlin.math.round(f!!.per100.kcal * 10) / 10)          // 174.5 кДж → 41.7 ккал
        assertEquals(3.0, f.per100.p)
        assertEquals(null, web.sosedi("кефир депи"))                              // «молоко депи» — не кефир
        assertEquals(408.5, WebFood.labelMacro(408.5, 8.9, 28.1, 30.0)?.kcal)    // уже ккал — как есть
        assertEquals(240.0, WebFood.labelMacro(240.0, 18.3, 18.3, null)?.kcal)    // моцарелла без углеводов — сходится
        assertEquals(null, WebFood.labelMacro(70.0, 16.0, null, 1.2))            // творог 9% без жиров — пропуск
        assertEquals(null, WebFood.labelMacro(null, null, null, null))

        val pasta = """{"data":[{"id":1,"name":"32163 макароны с ветчиной 300г"},{"id":2,"name":"макароны спагетти 450г"}]}"""
        val plain = WebFood(fetch = { url -> when { "/v2/products/search" in url -> pasta
            url.endsWith("/products/2/10") -> """{"calorie":"350","protein":"12","fat":"1.5","carbohydrate":"71"}"""
            url.endsWith("/products/1/10") -> """{"calorie":"186","protein":"17.4","fat":"10.7","carbohydrate":"4.9"}"""; else -> null } })
        assertEquals(350.0, plain.sosedi("макароны")?.per100?.kcal)              // ближе к запросу, а не готовое блюдо
    }
}
