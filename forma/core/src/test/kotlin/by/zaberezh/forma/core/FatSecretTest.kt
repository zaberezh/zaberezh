package by.zaberezh.forma.core

import by.zaberezh.forma.core.food.FatSecret
import by.zaberezh.forma.core.food.ShopFinder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FatSecretTest {
    // строки выдачи mobile.fatsecret.ru (сокращено, разметка как на сайте)
    private fun row(href: String, name: String, brand: String?, line: String, other: String? = null) = """
					<tr>
						<td colspan="3" onclick="window.location.href='$href'">
							<div class="next-link">
								<a class="inner-link" href="$href">$name</a>${brand?.let { "&nbsp;&nbsp;<a class=\"brand\" href=\"/x\">($it)</a>" } ?: ""}
								<div class="nowrap small-text">
									$line
								</div>
								${other?.let { "<div class=\"nowrap small-text\">Другое количество:\n\t\t\t$it, <i>подробнее ...</i>\n</div>" } ?: ""}
								<a class="inner-link" href="/search?q=x">Похожие</a>
							</div>
						</td>
					</tr>"""

    private val html = "<table class=\"list\">" +
        row("/p/provi/pirog-s-myasom-i-syrom/100g", "Пирог с Мясом и Сыром", "Провиант ", "в 100г - Калории: 376ккал | Жир: 22,90г | Углев: 29,10г | Белк: 13,00г") +
        row("/p/obshii/pirog-s-myasom", "Пирог с Мясом", null, "в 1 кусок - Калории: 313ккал | Жир: 7.77г | Углев: 44.82г | Белк: 14.33г",
            "1 порция - 313ккал, 100 г - 285ккал") +
        row("/p/lotte/choco-pie/1", "Choco Pie", "Lotte", "в 1 пирожное (28г) - Калории: 106ккал | Жир: 5,00г | Углев: 18,20г | Белк: 1,12г") +
        row("/p/obshii/borsch", "Борщ", null, "в 1 чашка - Калории: 78ккал | Жир: 4.02г | Углев: 8.26г | Белк: 3.36г") +   // нет «100 г» — массу не знаем
        "</table>"

    @Test fun parsesSearchRowsPer100() {
        val items = FatSecret.parse(html)
        assertEquals(listOf("Пирог с Мясом и Сыром (Провиант)", "Пирог с Мясом", "Choco Pie (Lotte)"), items.map { it.name })
        val pie = items[0]
        assertEquals(376.0, pie.per100!!.kcal, 0.01); assertEquals(13.0, pie.per100!!.p, 0.01); assertNull(pie.pieceGrams)
        val slice = items[1]                                   // «1 кусок» при «100 г — 285 ккал»: кусок ≈ 110 г
        assertEquals(313.0 / 285 * 100, slice.pieceGrams!!, 0.01); assertEquals(285.0, slice.per100!!.kcal, 0.5)
        assertEquals("1 кусок", slice.serving)
        val choco = items[2]
        assertEquals(28.0, choco.pieceGrams); assertEquals(106.0 / 28 * 100, choco.per100!!.kcal, 0.5)
    }

    @Test fun findsDishAndCountsPieces() {
        val f = ShopFinder(listOf(FatSecret({ html })))
        val pie = assertNotNull(f.find("пирог с мясом и сыром"))
        assertEquals("Пирог с Мясом и Сыром (Провиант)", pie.name)
        val two = assertNotNull(f.find("2 чокопая lotte"))
        assertEquals(56.0, two.grams)                          // 2 × 28 г
        val one = assertNotNull(f.find("пирог с мясом"))
        assertEquals("1 кусок — поправь, если больше", one.source.substringAfter(" · "))
    }
}
