package by.zaberezh.forma.core.food

import java.net.URLEncoder

/**
 * Добавки к блюду едят порцией, а не упаковкой: соус, сметана, варенье, сыр, масло…
 * Порция (г) по основе слова; null — это не добавка.
 */
private val CONDIMENTS = listOf(
    "кетчуп" to 20.0, "майонез" to 20.0, "соус" to 20.0, "горчиц" to 10.0, "аджик" to 15.0, "хрен" to 10.0,
    "сметан" to 30.0, "сливк" to 20.0, "сгущ" to 20.0, "варень" to 20.0, "джем" to 20.0, "конфитюр" to 20.0, "повидл" to 20.0,
    "мед" to 15.0, "мёд" to 15.0, "сироп" to 15.0, "топпинг" to 15.0, "паст" to 15.0, "нутелл" to 15.0,
    "сыр" to 20.0, "масл" to 10.0, "сахар" to 5.0, "кунжут" to 5.0,
)

fun condimentPortion(name: String): Double? {
    val n = name.lowercase().replace('ё', 'е')
    if (Regex("сырок|сырник|сырники|творожн").containsMatchIn(n)) return null // это отдельные блюда, не добавка
    // добавка — это само блюдо («соус барбекю», «натуральный мед»), а не вкус («чипсы сметана и лук»)
    val head = Regex("\\p{L}+").findAll(n).take(2).map { it.value }.toList()
    return CONDIMENTS.firstOrNull { (stem, _) -> head.any { it.startsWith(stem.replace('ё', 'е')) } }?.second
}

/**
 * Обычная порция блюда — если вес не написан, а источник его не знает или знает плохо.
 * piece — штучное (пирожок, котлета, кусок пирога): считается штуками; иначе — порция (тарелка супа, плов).
 */
data class Typical(val grams: Double, val piece: Boolean, val label: String = if (piece) "шт." else "порция")

/**
 * Сколько съедено, когда вес не написан: «тарелка борща» → 300 г, «2 пирожка» → 2 × вес пирожка, «сырники» → 3 шт.,
 * «плов» → 250 г. Количество и «тарелка/порция/кусок/стакан» берутся из запроса, вес штуки — у источника, если знает.
 */
object Dish {
    private val TYPICAL: List<Pair<Regex, Typical>> = listOf(
        "суп(а|у|ом|е|ы|чик\\p{L}*)?|борщ\\p{L}*|щи|щей|солянк\\p{L}*|уха|уху|ухи|рассольник\\p{L}*|харчо|окрошк\\p{L}*|бульон\\p{L}*|" +
            "похлебк\\p{L}*|шурп\\p{L}*|лагман\\p{L}*|свекольник\\p{L}*|гаспачо" to Typical(300.0, false, "тарелка"),
        "плов\\p{L}*|макарон\\p{L}*|паст[аыуе]|спагетти|лапш\\p{L}*|ризотто|рагу|гуляш\\p{L}*|жарко[ей]|каш[аиуе]|пюре|гречк\\p{L}*|" +
            "булгур\\p{L}*|кускус\\p{L}*|перловк\\p{L}*|пшенк\\p{L}*|фунчоз\\p{L}*|удон|рамен|бефстроганов|азу|лазань\\p{L}*|рис|риса|рисом" to Typical(250.0, false),
        "пельмен\\p{L}*|вареник\\p{L}*|манты|мантов|хинкали|равиоли" to Typical(220.0, false),
        "салат\\p{L}*|оливье|винегрет\\p{L}*|шуб[аыу]|цезар[ьяю]|мимоз[аыу]" to Typical(150.0, false),
        "запеканк\\p{L}*|омлет\\p{L}*|яичниц\\p{L}*" to Typical(180.0, false),
        "шашлык\\p{L}*|стейк\\p{L}*" to Typical(200.0, false),
        "пирог|пирога|пироги|пирогов|пицц\\p{L}*|кулебяк\\p{L}*" to Typical(120.0, true, "кусок"),
        "торт\\p{L}*" to Typical(100.0, true, "кусок"),
        "пирож(ок|ка|ки|ков|ку)|чебурек\\p{L}*|беляш\\p{L}*|самс\\p{L}*" to Typical(80.0, true),
        "блин(а|ы|ов)?|блинчик\\p{L}*" to Typical(60.0, true),
        "олад\\p{L}*" to Typical(40.0, true),
        "сырник\\p{L}*" to Typical(50.0, true),
        "драник\\p{L}*" to Typical(55.0, true),
        "котлет\\p{L}*|биточ\\p{L}*|шницел\\p{L}*|зраз\\p{L}*" to Typical(90.0, true),
        "тефтел\\p{L}*|фрикадел\\p{L}*" to Typical(40.0, true),
        "голубц\\p{L}*|голубец" to Typical(140.0, true),
        "бутерброд\\p{L}*|сэндвич\\p{L}*|тост\\p{L}*" to Typical(80.0, true),
        "шаурм\\p{L}*|шаверм\\p{L}*|буррито" to Typical(350.0, true),
        "хачапури" to Typical(300.0, true),
        "булк\\p{L}*|булочк\\p{L}*|круассан\\p{L}*|ватрушк\\p{L}*|слойк\\p{L}*|пончик\\p{L}*|кекс\\p{L}*|маффин\\p{L}*" to Typical(70.0, true),
    ).map { (rx, t) -> Regex(rx) to t }

    /** Первое слово блюда, у которого есть обычная порция: «куриный суп» → суп, «пюре с котлетой» → пюре. */
    fun typical(dish: String): Pair<String, Typical>? = words(dish).firstNotNullOfOrNull { w ->
        TYPICAL.firstOrNull { it.first.matches(w) }?.let { w to it.second }
    }

    /** «тарелка», «порция», «кусок», «стакан» в запросе: «2 тарелки борща», «кусок пиццы». */
    enum class Unit(val label: String, val rx: Regex) {
        PLATE("тарелка", Regex("тарел\\p{L}*|миск\\p{L}*")),
        PORTION("порция", Regex("порци\\p{L}*")),
        PIECE("кусок", Regex("кус(ок|ка|ку|ков|очек|очка|очков)|ломтик\\p{L}*")),
        GLASS("стакан", Regex("стакан\\p{L}*|кружк\\p{L}*|чашк\\p{L}*")),
    }

    /** Что заказано: сколько, в чём и само блюдо (как написал человек — для поиска). */
    data class Order(val count: Double?, val unit: Unit?, val dish: String)

    private val VERBS = setOf("съел", "съела", "съели", "поел", "поела", "выпил", "выпила", "ел", "ела", "немного")

    fun order(part: String): Order {
        var s = Menu.numberWords(part)
            .replace(Regex("\\d+(?:[.,]\\d+)?\\s*(?:кг|гр|грамм\\p{L}*|г|мл|л)(?!\\p{L})"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() && it !in VERBS }.joinToString(" ")   // «съел 3 сырника» → «3 сырника»
        var count: Double? = null
        Regex("^\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:шт\\.?|штук\\p{L}*|x|х)?\\s+").find(s)?.let { m ->
            count = m.groupValues[1].replace(',', '.').toDoubleOrNull(); s = s.removeRange(m.range)
        }
        Regex("(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\s*(?:шт\\.?|штук\\p{L}*)(?!\\p{L})").find(s)?.let { m ->   // «пирожок 2 шт»
            if (count == null) count = m.groupValues[1].replace(',', '.').toDoubleOrNull()
            s = s.removeRange(m.range)
        }
        var ws = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        val unit = ws.firstOrNull()?.let { w -> Unit.entries.firstOrNull { it.rx.matches(w) } }
        if (unit != null) ws = ws.drop(1)
        return Order(count, unit, ws.joinToString(" ").trim())
    }

    /** Множественное число без счёта: «сырники», «блины», «котлеты» — съедено несколько штук. */
    fun plural(w: String): Boolean = w.length >= 4 && (w.endsWith("ы") || w.endsWith("и")) && w !in INDECLINABLE
    private val INDECLINABLE = setOf("хачапури", "спагетти", "равиоли", "хинкали", "суши", "салями", "чили", "киви", "оливье")

    /** Основы слова без окончания и с беглой гласной: пирожок ~ пирожки ~ пирожка, голубец ~ голубцы, но пирог ≠ пирожок. */
    fun roots(w0: String): Set<String> {
        val w = w0.lowercase().replace('ё', 'е')
        val out = mutableSetOf(w)
        ENDINGS.filter { w.endsWith(it) && w.length - it.length >= 3 }.forEach { out += w.dropLast(it.length) }
        Regex("^(\\p{L}{3,})[ое]([кц])$").find(w)?.let { out += it.groupValues[1] + it.groupValues[2] }
        return out
    }
    private val ENDINGS = listOf("ами", "ями", "ов", "ев", "ей", "ом", "ем", "ой", "ах", "ях", "ы", "и", "а", "я", "у", "ю", "е", "о", "ь")
    fun sameWord(a: String, b: String) = roots(a).any { it in roots(b) }

    /** Родительный множественного — в именительный, его поиск сайтов понимает: пирожков → пирожки, блинов → блины, пельменей → пельмени. */
    fun lemma(w: String): String? = when {
        w.length >= 5 && (w.endsWith("ов") || w.endsWith("ев")) -> w.dropLast(2) + if (w[w.length - 3] in "гкхжшчщ") "и" else "ы"
        w.length >= 5 && w.endsWith("ей") -> w.dropLast(2) + "и"
        else -> null
    }

    private fun words(s: String) = Regex("\\p{L}+(?:-\\p{L}+)?").findAll(s.lowercase().replace('ё', 'е')).map { it.value }.toList()

    /**
     * Сколько положить и пометка «поправь». piece — вес одной штуки у источника (FatSecret: «1 пирожок» = 75 г),
     * portion — его порция («1 порция» = 155 г); null — источник не знает. Явный вес из запроса — главнее всего.
     */
    fun portion(part: String, piece: Double? = null, portion: Double? = null, pieceLabel: String = "шт."): Pair<Double, String?> {
        WebFood.gramsOf(part)?.takeIf { it > 0 }?.let { return it to null }
        val o = order(part)
        val (word, t) = typical(o.dish) ?: (null to null)
        val n = o.count ?: 1.0
        fun g(x: Double) = Math.round(x).toString()
        fun num(x: Double) = if (x == Math.floor(x)) x.toLong().toString() else x.toString()
        return when (o.unit) {
            Unit.PLATE, Unit.PORTION -> {
                // тарелка штучного (блинов, пельменей) — несколько штук
                val one = t?.takeIf { !it.piece }?.grams ?: portion ?: t?.let { it.grams * if (it.grams < 70) 3 else 2 } ?: 250.0
                one * n to "${if (n == 1.0) "" else num(n) + " × "}${o.unit.label} ≈${g(one)} г — поправь, если не так"
            }
            Unit.PIECE -> {
                val one = piece ?: t?.takeIf { it.piece }?.grams ?: 100.0
                one * n to "${if (n == 1.0) "" else num(n) + " × "}кусок ≈${g(one)} г — поправь, если не так"
            }
            Unit.GLASS -> 250.0 * n to "${if (n == 1.0) "" else num(n) + " × "}стакан ≈250 г"
            null -> {
                val pieceG = piece ?: t?.takeIf { it.piece }?.grams
                val label = if (piece == null && t?.piece == true) t.label else pieceLabel
                when {
                    o.count != null && pieceG != null -> pieceG * n to "${num(n)} $label по ${g(pieceG)} г — поправь, если не так"
                    o.count != null -> (t?.grams ?: portion ?: 100.0).let { it * n to "${num(n)} × ≈${g(it)} г — поправь, если не так" }
                    t != null && !t.piece -> t.grams to "${t.label} ≈${g(t.grams)} г — поправь, если не так"
                    pieceG != null -> {
                        val k = if (plural(word ?: words(o.dish).firstOrNull().orEmpty())) (if (pieceG < 70) 3 else 2) else 1
                        pieceG * k to "$k $label${if (k > 1) " по " + g(pieceG) + " г" else " ≈" + g(pieceG) + " г"} — поправь, если не так"
                    }
                    portion != null -> portion to "порция ≈${g(portion)} г — поправь, если не так"
                    else -> 100.0 to "вес не указан — стоит 100 г, поправь"
                }
            }
        }
    }
}

/**
 * Поиск калорийности в интернете прямо с телефона — вместо веб-поиска посредника API.
 * Возвращает строки вокруг «ккал» из выдачи поисковика: их читает Claude как подсказку.
 */
object WebHints {
    fun find(query: String, fetch: (String) -> String? = Edostavka::httpGet): List<String> {
        val q = URLEncoder.encode("$query калорийность кбжу на 100 г", "UTF-8")
        for (src in listOf("https://html.duckduckgo.com/html/?q=$q", "https://www.bing.com/search?q=$q")) {
            val text = fetch(src)?.let(Edostavka::textOf) ?: continue
            val out = Regex("ккал|калори", RegexOption.IGNORE_CASE).findAll(text).map { m ->
                text.substring((m.range.first - 160).coerceAtLeast(0), (m.range.last + 120).coerceAtMost(text.length)).trim()
            }.distinct().take(6).toList()
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }
}
