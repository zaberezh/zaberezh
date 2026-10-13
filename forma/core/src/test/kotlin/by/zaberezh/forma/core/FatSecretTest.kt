package by.zaberezh.forma.core

import by.zaberezh.forma.core.food.Dish
import by.zaberezh.forma.core.food.FatSecret
import by.zaberezh.forma.core.food.Menus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FatSecretTest {
    // строки выдачи mobile.fatsecret.ru — разметка и цифры как на сайте (выдача 13.10.2026, сокращено)
    private fun row(href: String, name: String, brand: String?, line: String, other: String? = null) = """
					<tr>
						<td colspan="3" onclick="window.location.href='$href'">
							<div class="next-link">
								<a class="inner-link" href="$href">$name</a>${brand?.let { "&nbsp;&nbsp;<a class=\"brand\" href=\"/b\">($it)</a>" } ?: ""}
								<div class="nowrap small-text">
									$line
								</div>
								${other?.let { "<div class=\"nowrap small-text\">Другое количество:\n\t\t\t$it, <i>подробнее ...</i>\n</div>" } ?: ""}
								<a class="inner-link" href="/search?q=x">Похожие</a>
							</div>
						</td>
					</tr>"""

    private fun page(vararg rows: String) = "<table class=\"list\">" + rows.joinToString("") + "</table>"
    private fun kcal(k: String, f: String, c: String, p: String) = "Калории: ${k}ккал | Жир: ${f}г | Углев: ${c}г | Белк: ${p}г"

    private val pies = page(
        row("/общий/Пирожок-с-Грибами", "Пирожок с Грибами", null, "в 1 пирожок - " + kcal("181", "8.82", "21.94", "4.00"), "1 порция - 363ккал, 100 г - 242ккал"),
        row("/Окей/Пирог", "Пирог с Картофелем и Грибами (Постный)", "Окей", "в 100г - " + kcal("192", "3,50", "34,70", "5,30")),
        row("/Сормовский/Пирог", "Пирог с Грибами и Луком", "Сормовский Хлеб", "в 100г - " + kcal("377", "22,90", "36,80", "5,90")),
        row("/Дворик/Пирог", "Пирог с курой и грибами", "Пироговый Дворик", "в 100г - " + kcal("360", "23,00", "28,00", "9,00")),
    )

    private val blini = page(
        row("/МК/Блины", "Блины с Творогом и Изюмом ", "Мясной Король", "в 100г - " + kcal("168", "4,50", "24,10", "7,60")),
        row("/Ермолино/Блины", "Блины с Творогом и Изюмом ", "Ермолино", "в 100g - " + kcal("274", "10,00", "38,00", "8,00")),
        row("/общий/Блины-с-Творогом", "Блины с Творогом", null, "в 1 блин - " + kcal("116", "2.96", "14.74", "7.20"), "1 порция - 231ккал, 100 г - 193ккал"),
        row("/ВВ/Блины", "Блины с Творогом и Изюмом", "ВкусВилл", "в 100г - " + kcal("218", "8,80", "26,80", "8,00")),
        row("/Calor/Блины", "Блины с Творогом и Изюмом", "Caloristika", "в 1 порция (200г) - " + kcal("347", "12,00", "45,00", "14,80")),
        row("/Горторг/Блины", "Блины с Творогом и Изюмом", "Горторг", "в 100г - " + kcal("196", "4,00", "29,10", "10,90")),
        row("/Центрум/Блины", "Блины с Творогом и Изюмом", "Центрум", "в 100г - " + kcal("200", "4,00", "29,00", "11,00")),
        row("/общий/Булочки", "Сладкие Булочки (с Корицей и Изюмом)", null, "в 100 г - " + kcal("372", "16.4", "50.9", "6.2")),
    )

    private val other = page(
        row("/общий/Котлета-Куриная", "Котлета Куриная", null, "в 1 котлета - " + kcal("214", "9.68", "12.99", "17.82"), "1 порция - 214ккал, 100 г - 226ккал"),
        row("/общий/Борщ", "Борщ", null, "в 1 чашка - " + kcal("78", "4.02", "8.26", "3.36"), "1 порция - 78ккал, 100 г - 32ккал"),
        row("/общий/Плов-с-Курицей", "Плов с Курицей", null, "в 100 г - " + kcal("223", "9.08", "23.19", "10.84"), "1 чашка - 459ккал, 1 порция - 345ккал"),
        row("/lotte/choco-pie/100", "Choco Pie", "Lotte", "в 100г - " + kcal("440", "18,70", "63,90", "3,90")),
        row("/lotte/choco-pie/1", "Choco Pie", "Lotte", "в 1 пирожное (28г) - " + kcal("106", "5,00", "18,20", "1,12")),
        row("/arahis", "Арахисовое Масло с Кусочками (с Солью)", null, "в 2 ст л (32г) - " + kcal("188", "15.98", "6.90", "7.70")),
    )

    private val cabbage = page(
        row("/общий/Пирожок-с-Капустой", "Пирожок с Капустой", null, "в 1 пирожок - " + kcal("190", "7.83", "25.49", "4.48"), "1 порция - 380ккал, 100 г - 253ккал"),
        row("/Перекресток/Пирожки", "Пирожки с Капустой", "Перекресток", "в 100г - " + kcal("300", "10,00", "42,00", "6,00")),
    )
    private val garbage = page(row("/syr", "Сыр с Плесенью", null, "в 100 г - " + kcal("353", "28.74", "2.34", "21.4")))

    /** Выдача по запросу: что спросили — то и вернули (чтобы видеть, как ищется). */
    private fun site(vararg pages: Pair<String, String>, asked: MutableList<String> = mutableListOf()) = FatSecret({ url ->
        val q = java.net.URLDecoder.decode(url.substringAfter("q="), "UTF-8")
        asked += q
        pages.firstOrNull { it.first == q }?.second ?: garbage
    })

    @Test fun parsesRowsWithServings() {
        val e = FatSecret.parse(blini)
        assertEquals(8, e.size)
        val generic = e.first { it.generic && it.name == "Блины с Творогом" }
        assertEquals("Блины с Творогом", generic.name)
        assertEquals(193.0, generic.per100.kcal, 0.5)
        assertEquals(60.1, generic.servings.first { it.unit == "блин" }.grams, 0.1)          // 116 ккал при 193 на 100 г
        assertEquals(274.0, e.first { it.brand == "Ермолино" }.per100.kcal, 0.1)             // «100g» латиницей — тоже 100 г
        assertEquals(173.5, e.first { it.brand == "Caloristika" }.per100.kcal, 0.1)          // 347 ккал на 200 г
        val pb = FatSecret.parse(other).first { it.name.startsWith("Арахис") }
        assertNull(pb.piece("масло", loose = true))                                           // «2 ст л» — не штука
        assertTrue(FatSecret.parse(page(row("/x", "Соус", null, "в 2 ст л - " + kcal("50", "1", "8", "1")))).isEmpty())   // без массы — не пересчитать
    }

    @Test fun plainDishBeatsBrandsAndExtraIngredients() {
        val pie = assertNotNull(site("пирог с грибами" to pies).find("пирог с грибами"))
        assertEquals("Пирожок с грибами", pie.name)                                           // не «пирог с курой и грибами»
        assertEquals(242.0, pie.per100.kcal, 0.5)
        assertEquals(120.0, pie.grams)                                                        // кусок пирога, а не пирожок 75 г
        assertTrue("кусок" in pie.source, pie.source)
    }

    @Test fun typicalAmongBrandsWhenNoPlainDish() {
        val b = assertNotNull(site("блины с творогом и изюмом" to blini).find("блины с творогом и изюмом"))
        assertEquals("Блины с творогом и изюмом", b.name)
        assertEquals(200.0, b.per100.kcal, 0.5)                                               // медиана 168…274, а не первая марка
        assertTrue("типичное из 6" in b.source, b.source)
        assertEquals(180.3, b.grams, 0.2)                                                     // 3 блина по 60 г — вес блина у общей записи
    }

    @Test fun portionsFromQueryAndServings() {
        val f = site("котлета куриная" to other, "котлеты куриные" to other, "борщ" to other, "борща" to other, "плов с курицей" to other,
            "lotte choco pie" to other)
        assertEquals(94.7, assertNotNull(f.find("котлета куриная")).grams, 0.1)                // 1 котлета у FatSecret
        assertEquals(189.4, assertNotNull(f.find("2 котлеты куриные")).grams, 0.2)
        val borsch = assertNotNull(f.find("борщ"))
        assertEquals(300.0, borsch.grams); assertEquals(32.0, borsch.per100.kcal, 0.5)         // тарелка, а не «чашка» 243 г
        assertEquals(600.0, assertNotNull(f.find("2 тарелки борща")).grams)
        assertEquals(150.0, assertNotNull(f.find("полтарелки борща")).grams)
        assertEquals(250.0, assertNotNull(f.find("плов с курицей")).grams)
        assertEquals(350.0, assertNotNull(f.find("плов с курицей 350г")).grams)               // явный вес главнее
        assertEquals(28.0, assertNotNull(f.find("lotte choco pie")).grams)                    // штука у той же записи с другой порцией
    }

    @Test fun genitivePluralIsAskedInNominative() {
        val asked = mutableListOf<String>()
        val f = site("пирожки с капустой" to cabbage, asked = asked)
        val p = assertNotNull(f.find("5 пирожков с капустой"))
        assertEquals(listOf("пирожков с капустой", "пирожки с капустой"), asked)               // «пирожков» FatSecret не понимает
        assertEquals("Пирожок с капустой", p.name)
        assertEquals(375.5, p.grams, 0.5)                                                     // 5 × 75 г
        assertNull(site().find("пирожки с вареньем из шишек"))
    }

    @Test fun orderAndTypicalPortions() {
        assertEquals(Dish.Order(2.0, Dish.Unit.PLATE, "борща"), Dish.order("2 тарелки борща"))
        assertEquals(Dish.Order(null, Dish.Unit.PIECE, "пиццы"), Dish.order("кусок пиццы"))
        assertEquals(Dish.Order(2.0, null, "пирожок"), Dish.order("пирожок 2 шт"))
        assertEquals(Dish.Order(3.0, null, "сырника"), Dish.order("съел 3 сырника"))
        assertEquals(250.0, Dish.portion("плов").first)
        assertEquals(150.0, Dish.portion("сырники").first)                                    // без числа — 3 штуки
        assertEquals(50.0, Dish.portion("сырник").first)
        assertEquals(180.0, Dish.portion("котлеты").first)                                    // 2 по 90 г
        assertEquals(160.0, Dish.portion("2 пирожка").first)
        assertEquals(120.0, Dish.portion("кусок пиццы").first)
        assertEquals(180.0, Dish.portion("тарелка блинов").first)                            // тарелка штучного — несколько штук
        assertEquals(150.0, Dish.portion("курица 150г").first)
        assertEquals(100.0, Dish.portion("что-то непонятное").first)
        assertTrue(Dish.sameWord("пирожки", "пирожок") && Dish.sameWord("голубцы", "голубец") && !Dish.sameWord("пирог", "пирожок"))
        assertEquals("пирожки", Dish.lemma("пирожков")); assertEquals("блины", Dish.lemma("блинов")); assertEquals("пельмени", Dish.lemma("пельменей"))
    }

    @Test fun tableCountsPluralPieces() {
        fun g(t: String) = Menus.basic.resolve(t).items.single().grams
        assertEquals(150.0, g("блины"))                                                       // 3 блина, а не один
        assertEquals(150.0, g("сырники"))
        assertEquals(160.0, g("котлеты"))                                                     // 2 по 80 г
        assertEquals(50.0, g("блин"))
        assertEquals(100.0, g("2 блина"))
        assertEquals(150.0, g("драники"))                                                     // строка уже на порцию
    }
}
