package by.zaberezh.forma.core

import by.zaberezh.forma.core.food.Edostavka
import by.zaberezh.forma.core.food.shopResolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EdostavkaTest {
    private val product = """
        <html><head><title>Молочный коктейль Teos Pro обезжиренный, клубника, 330 г купить в Минске: недорого в интернет-магазине Едоставка</title></head>
        <body><script>var x = {"a":1}</script><div>Вкус клубника</div><div>Состав</div><p>Молоко обезжиренное…</p>
        <div><span>На 100 грамм</span></div>
        <div><div><span>9</span><span>Белки</span></div><div><span>0.15</span><span>Жиры</span></div>
        <div><span>4.8</span><span>Углеводы</span></div><div><span>56.55 ккал/ 240.15 кДж</span><span>Энергетическая ценность</span></div></div>
        <div>Бренд: Teos</div></body></html>
    """.trimIndent()
    private val search = """<a href="/product/2228210">Teos Pro клубника</a><a href="/product/2228208">Teos Pro шоколад</a>"""
    private val choco = product.replace("клубника, 330 г", "молочный шоколад, 330 г").replace(">9<", ">9.1<")

    private val edo = Edostavka(fetch = { url ->
        when {
            "search" in url -> search
            url.endsWith("2228210") -> product
            url.endsWith("2228208") -> choco
            else -> null
        }
    })

    @Test fun parsesProductPage() {
        val p = assertNotNull(edo.page("https://edostavka.by/product/2228210"))
        assertEquals("Молочный коктейль Teos Pro обезжиренный, клубника, 330 г", p.title)
        assertEquals(330.0, p.packGrams)
        val m = assertNotNull(p.per100)
        assertEquals(9.0, m.p); assertEquals(0.15, m.f); assertEquals(4.8, m.c); assertEquals(56.55, m.kcal)
    }

    @Test fun labelFirstOrder() {
        val m = assertNotNull(Edostavka.parseMacros("На 100 г: Белки 9 г, Жиры 0,15 г, Углеводы 4,8 г, 56,55 ккал"))
        assertEquals(9.0, m.p); assertEquals(0.15, m.f); assertEquals(4.8, m.c)
    }

    @Test fun findsRightFlavourAndPackWeight() {
        val r = shopResolve("теос про клубника", edo)
        assertEquals(1, r.items.size, r.toString())
        val it = r.items.single()
        assertTrue("клубника" in it.name); assertEquals(330.0, it.grams)
        assertEquals(185.0, it.total.kcal, 2.0); assertEquals(29.7, it.total.p, 0.1)
        assertEquals("high", it.conf); assertTrue(it.source.startsWith("https://edostavka.by/product/"))
    }

    @Test fun genericFoodsGoToClaude() {
        val r = shopResolve("гречка 200г, 2 яйца, теос про клубника 200г", edo)
        assertEquals(listOf("гречка 200г", "2 яйца"), r.unresolved)
        assertEquals(200.0, r.items.single().grams)
    }

    @Test fun unknownProductIsNotForced() {
        assertNull(edo.lookup("шаурма чизер папа донер"))
        assertEquals("теос про клубника", Edostavka.cleanQuery("2 теос про клубника 330г"))
    }
}
