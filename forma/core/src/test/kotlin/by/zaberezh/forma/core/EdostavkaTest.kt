package by.zaberezh.forma.core

import by.zaberezh.forma.core.food.Edostavka
import by.zaberezh.forma.core.food.FoodPipeline
import by.zaberezh.forma.core.food.PageRunner
import by.zaberezh.forma.core.food.ShopFinder
import by.zaberezh.forma.core.food.ShopMatch
import by.zaberezh.forma.core.food.Sosedi
import by.zaberezh.forma.core.food.pieceGrams
import by.zaberezh.forma.core.store.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EdostavkaTest {
    // ответы скрипта со страницы edostavka.by — в том виде, как их отдаёт сайт (снято с сайта)
    private val teos = """{"items":[
        {"id":"2228208","name":"Молочный коктейль Teos Pro обезжиренный, молочный шоколад, 330 г","pack":"","props":{"Белки":"9.1","Жиры":"0.15","Углеводы":"4.8","Энергетическая ценность":"57 ккал/ 241 кДж"}},
        {"id":"2228210","name":"Молочный коктейль Teos Pro обезжиренный, клубника, 330 г","pack":"","props":{"Белки":"9","Жиры":"0.15","Углеводы":"4.8","Энергетическая ценность":"56.55 ккал/ 240.15 кДж"}}]}"""
    private val kefir = """{"items":[{"id":"1922907","name":"Кефир детский Беллакт обогащенный бифидобактериями, 3.3%, 0.207 кг","pack":"",
        "props":{"Белки":"3","Жиры":"3.3","Углеводы":"4","Энергетическая ценность":"56,8 ккал/237,4 кДж"}}]}"""
    private val gouda = """{"items":[{"id":"2287615","name":"Сыр полутвердый Гауда Премиум 45%, ","pack":"200 г",
        "props":{"Белки":"26.2","Жиры":"24.9","Энергетическая ценность":"329 ккал /1367 кДж"}}]}"""
    private val sauce = """{"items":[{"id":"7","name":"Соус барбекю Heinz, 230 г","pack":"","props":{"Белки":"1","Жиры":"0.2","Углеводы":"25","Энергетическая ценность":"105 ккал"}}]}"""

    /** Страница edostavka: отвечает по тексту запроса в скрипте. */
    private fun page(vararg answers: Pair<String, String>) = PageRunner { js ->
        assertTrue(").search(" in js || ").product(" in js, "скрипт сайта: ${js.takeLast(60)}")
        answers.firstOrNull { (q, _) -> ".search(\"$q\")" in js }?.second ?: """{"items":[]}"""
    }

    private val edo = Edostavka(page("теос про клубника" to teos, "кефир детский депи" to kefir, "сыр гауда" to gouda, "соус барбекю" to sauce))
    private fun finder(e: Edostavka = edo, sosedi: (String) -> String? = { null }) = ShopFinder(listOf(e, Sosedi(sosedi)))

    @Test fun searchResultCarriesLabelMacros() {
        val k = edo.search("кефир детский депи").single()
        val m = assertNotNull(k.per100)
        assertEquals(3.0, m.p); assertEquals(3.3, m.f); assertEquals(4.0, m.c); assertEquals(56.8, m.kcal)
        assertEquals(207.0, k.packGrams)
        // гауда: углеводов нет в карточке, масса — из packagingInfo
        val g = edo.search("сыр гауда").single()
        assertEquals(329.0, g.per100?.kcal); assertEquals(0.0, g.per100?.c); assertEquals(200.0, g.packGrams)
        assertEquals("Сыр полутвердый Гауда Премиум 45%, 200 г", g.name)
        assertEquals(emptyList(), Edostavka.parseSearch("""{"error":"HTTP 403"}"""))
        assertEquals(emptyList(), Edostavka(null).search("кефир"))                 // нет браузера — edostavka пропускается
    }

    @Test fun findsRightFlavourAndPackWeight() {
        val it = assertNotNull(finder().find("теос про клубника"))
        assertTrue("клубника" in it.name, it.name); assertEquals(330.0, it.grams)   // бутылка напитка — целиком
        assertEquals(186.6, it.total.kcal, 1.0); assertEquals(29.7, it.total.p, 0.1)
        assertEquals("high", it.conf); assertTrue(it.source.startsWith("edostavka.by"), it.source)
    }

    @Test fun genericFoodsAndStatedGrams() {
        val r = finder().resolve(listOf("гречка 200г", "2 яйца", "теос про клубника 200г"))
        assertEquals(listOf("гречка 200г", "2 яйца"), r.unresolved)
        assertEquals(200.0, r.items.single().grams)
    }

    @Test fun unknownProductIsNotForced() {
        assertNull(finder(Edostavka(page("шаурма чизер папа донер" to teos))).find("шаурма чизер папа донер"))
        assertEquals("теос про клубника", Edostavka.cleanQuery("2 теос про клубника 330г"))
    }

    @Test fun condimentsArePortions() {
        assertEquals(20.0, by.zaberezh.forma.core.food.condimentPortion("соус барбекю"))
        assertEquals(30.0, by.zaberezh.forma.core.food.condimentPortion("сметана 20%"))
        assertEquals(20.0, by.zaberezh.forma.core.food.condimentPortion("Сыр Российский"))
        assertNull(by.zaberezh.forma.core.food.condimentPortion("сырники"))
        assertNull(by.zaberezh.forma.core.food.condimentPortion("теос про клубника"))
        assertEquals(20.0, finder().find("соус барбекю")?.grams)                   // порция, а не бутылка 230 г
        assertNull(by.zaberezh.forma.core.food.condimentPortion("чипсы lays сметана и лук"))   // сметана — вкус, а не добавка
        assertEquals(15.0, by.zaberezh.forma.core.food.condimentPortion("Натуральный мед, 250 г"))
    }

    @Test fun labelFirstOrder() {
        val m = assertNotNull(Edostavka.parseMacros("На 100 г: Белки 9 г, Жиры 0,15 г, Углеводы 4,8 г, 56,55 ккал"))
        assertEquals(9.0, m.p); assertEquals(0.15, m.f); assertEquals(4.8, m.c)
    }

    @Test fun webHintsFromSearchPage() {
        val page = "<html><body><div class='r'>Хот-дог — калорийность 290 ккал на 100 г, белки 10, жиры 17, углеводы 24</div></body></html>"
        val hints = by.zaberezh.forma.core.food.WebHints.find("хот-дог", fetch = { page })
        assertTrue(hints.single().contains("290 ккал"))
    }

    // ---- сравнение названий: как пишет человек и как товар назван в магазине ----

    @Test fun wordsMatchAcrossFormsAndAlphabets() {
        assertTrue(ShopMatch.same("банана", "банан")); assertTrue(ShopMatch.same("детский", "детское"))
        assertTrue(ShopMatch.same("lotte", "лотте")); assertTrue(ShopMatch.same("choco", "чоко"))
        assertTrue(ShopMatch.same("snickers", "сникерс")); assertTrue(ShopMatch.same("twix", "твикс"))
        assertTrue(ShopMatch.same("bounty", "баунти")); assertTrue(ShopMatch.same("кефир", "кефирный"))
        assertFalse(ShopMatch.same("сыр", "сырок")); assertFalse(ShopMatch.same("сырок", "сыр"))
        assertFalse(ShopMatch.same("кефир", "молоко"))
        assertEquals("лотте", ShopMatch.cyrillic("lotte")); assertEquals("чоко", ShopMatch.cyrillic("choco"))
    }

    @Test fun choosesTheProductNotJustSharedWords() {
        val q = "Печенье Lotte Choco Pie с ароматом банана"
        assertEquals(1.0, ShopMatch.score(q, "печенье лотте чокопай глазированное банан 336 г"))
        assertTrue(ShopMatch.fit(q, "Пирожное Lotte Choco Pie банан").ok)        // другое слово «что это», но всё остальное совпало
        assertFalse(ShopMatch.fit(q, "мороженое сливочное с ароматом банана и молочно-шоколадным наполнителем 250г").ok)
        assertFalse(ShopMatch.fit(q, "печенье-сэндвич пробис с протеином и начинкой крем какао-банан 75г").ok)
        assertTrue(ShopMatch.fit("кефир детский депи", "кефир детский 3.2% 0.25л").ok)
        assertFalse(ShopMatch.fit("кефир детский депи", "молоко детское депи 3.2% 250мл").ok)   // не кефир
        assertTrue(ShopMatch.fit("чоко пай", "изделие мучное кондитерское в глазури чоко пай 6 шт*30 г").ok)
        assertTrue(ShopMatch.fit("чокопай", "изделие мучное кондитерское в глазури чоко пай 6 шт*30 г").ok)
        // жирность — тоже признак: «творог савушкин 5%» — не 2%
        assertTrue(ShopMatch.score("творог савушкин 5%", "Творог Савушкин классический, 5%, 300 г") > ShopMatch.score("творог савушкин 5%", "творог савушкин 2% 180г"))
        assertFalse(ShopMatch.fit("снежок савушкин", "напиток дарида снежок газированный 0.75л").ok)   // другой «снежок»
    }

    @Test fun shorterQueriesWhenTheLongOneFindsNothing() {
        assertEquals(listOf("печенье lotte choco pie банана", "печенье лотте чоко пие банана", "lotte choco pie банана", "lotte choco pie"),
            finder().variants("Печенье Lotte Choco Pie с ароматом банана"))
        assertEquals(listOf("кефир детский депи", "детский депи", "кефир детский"), finder().variants("кефир детский депи 200г"))
    }

    @Test fun packOfCookiesIsNotOnePortion() {
        fun g(name: String, pack: Double) = ShopFinder.gramsFor("что-то", by.zaberezh.forma.core.food.ShopItem("s", "1", name, "", packGrams = pack)).first
        assertEquals(100.0, g("Печенье Лотте Чокопай Глазированное Клубника 168 г", 168.0))
        assertEquals(45.0, g("Сырок глазированный Брест-Литовск ваниль 45 г", 45.0))
        assertEquals(140.0, g("Чипсы Lay's сметана и лук, 140 г", 140.0))
        assertEquals(330.0, g("Молочный коктейль Teos Pro, 330 г", 330.0))
    }

    @Test fun pieceWeightFromName() {
        assertEquals(30.0, pieceGrams("изделие мучное кондитерское в глазури чоко пай 6 шт*30 г"))
        assertEquals(28.0, pieceGrams("Печенье Lotte Choco Pie, 4 шт. по 28 г"))
        assertEquals(30.0, pieceGrams("Choco Pie 30 г х 12 шт"))
        assertNull(pieceGrams("Сыр Гауда 45%, 200 г")); assertNull(pieceGrams("Вода 6х1.5 л"))
    }

    // ---- «Соседи» (их API) и оба магазина сразу ----

    private val sosediSearch = """{"took":9,"data":[
        {"id":300378825,"name":"4810206007507 мороженое сливочное с ароматом банана и молочно-шоколадным наполнителем 250г"},
        {"id":300379924,"name":"8690504961482 печенье-сэндвич пробис с протеином и начинкой крем какао-банан 75г"},
        {"id":300375117,"name":"4607176440935 печенье лотте чокопай глазированное банан 336 г "},
        {"id":300362762,"name":"4810319013006 кефир детский 3.2% 0.25л"},
        {"id":300376668,"name":"4810319008408 молоко детское депи 3.2% 250мл"},
        {"id":300382419,"name":"4607084351378 изделие мучное кондитерское в глазури чоко пай 6 шт*30 г"}]}"""
    private val chocoPieOnly = """{"data":[{"id":300382419,"name":"4607084351378 изделие мучное кондитерское в глазури чоко пай 6 шт*30 г"}]}"""
    private fun sosedi(url: String): String? = when {
        "/v2/products/search" in url && java.net.URLDecoder.decode(url.substringAfter("query="), "UTF-8") == "чоко пай" -> chocoPieOnly
        "/v2/products/search" in url -> sosediSearch
        url.endsWith("/products/300375117/10") -> """{"id":300375117,"slug":"pechene-lotte-chokopai-banan","name":"Печенье Лотте Чокопай глазированное банан 336 г",
            "calorie":"420","protein":"4.5","fat":"17","carbohydrate":"62","weight":"0.336"}"""
        url.endsWith("/products/300362762/10") -> """{"id":300362762,"slug":"kefir-detskii-depi","name":"Кефир детский 3.2% 0.25л",
            "calorie":"236","protein":"2.9","fat":"3.2","carbohydrate":"4.1","weight":"0.25"}"""
        url.endsWith("/products/300382419/10") -> """{"id":300382419,"name":"Изделие мучное кондитерское в глазури Чоко Пай 6 шт*30 г",
            "calorie":"440","protein":"4","fat":"18","carbohydrate":"66"}"""
        else -> null
    }

    @Test fun chocoPieFromSosediByItsRussianName() {
        val f = finder(sosedi = ::sosedi)
        val it = assertNotNull(f.find("Печенье Lotte Choco Pie с ароматом банана"))
        assertEquals("Печенье Лотте Чокопай глазированное банан 336 г", it.name)
        assertEquals(420.0, it.per100.kcal)
        assertEquals(100.0, it.grams); assertTrue("поправь" in it.source, it.source)   // пачка 336 г — не порция
        assertEquals(60.0, assertNotNull(f.find("2 чоко пай")).grams)                // две штуки по 30 г
    }

    @Test fun brokenCardFallsBackToTheSameProductOfAnotherBrand() {
        // у «Соседей» карточка Lotte Choco Pie банан с испорченными цифрами — берём «Чоко Пай»
        val broken: (String) -> String? = { url ->
            when {
                "/v2/products/search" in url && java.net.URLDecoder.decode(url.substringAfter("query="), "UTF-8").startsWith("чоко") -> chocoPieOnly
                "/v2/products/search" in url -> """{"data":[{"id":300375117,"name":"4607176440935 печенье лотте чокопай глазированное банан 336 г "}]}"""
                url.endsWith("/products/300375117/10") -> """{"name":"Печенье Лотте Чокопай глазированное банан 336 г","calorie":"0.34","protein":"23.5","fat":"52","carbohydrate":""}"""
                url.endsWith("/products/300382419/10") -> """{"name":"Изделие мучное кондитерское в глазури Чоко Пай 6 шт*30 г","calorie":"440","protein":"4","fat":"18","carbohydrate":"66"}"""
                else -> null
            }
        }
        val it = assertNotNull(finder(sosedi = broken).find("Печенье Lotte Choco Pie с ароматом банана"))
        assertEquals("Изделие мучное кондитерское в глазури Чоко Пай 6 шт*30 г", it.name)
        assertEquals(30.0, it.grams); assertEquals("medium", it.conf)
        assertTrue(it.source.startsWith("sosedi-dostavka.by · похожий товар"), it.source)
        assertNull(ShopFinder(listOf(Edostavka(null), Sosedi(broken))).find("кефир детский депи"))   // без латиницы — не подменяем
    }

    @Test fun bothShopsCloserNameWins() {
        // edostavka: «Кефир детский Беллакт…», «Соседи»: «кефир детский 3.2%» (тот самый Депи) — меньше лишних слов
        val it = assertNotNull(finder(sosedi = ::sosedi).find("кефир детский депи"))
        assertEquals("Кефир детский 3.2% 0.25л", it.name)
        assertEquals(56.4, it.per100.kcal, 0.1)                                          // 236 — это кДж
        assertEquals(250.0, it.grams)                                                    // бутылочка целиком
        assertTrue(it.source.startsWith("sosedi-dostavka.by"), it.source)
    }

    @Test fun pipelineUsesTheBrowserPage() {
        val r = FoodPipeline(MemoryStore(), fetch = { null }, edostavka = page("теос про клубника" to teos)).run("теос про клубника")
        assertEquals("Молочный коктейль Teos Pro обезжиренный, клубника, 330 г", r.items.single().name)
        assertEquals("1 edostavka.by", r.how)
        // без браузера — до Соседей; там нет — ни к чему не притягивает
        assertEquals(listOf("теос про клубника"), FoodPipeline(MemoryStore(), fetch = { null }).run("теос про клубника").missing)
    }
}
