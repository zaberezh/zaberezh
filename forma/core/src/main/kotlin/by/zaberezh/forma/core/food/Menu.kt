package by.zaberezh.forma.core.food

/** Позиция меню заведения: масса порции и КБЖУ на 100 г ровно как в официальной таблице. */
data class MenuItem(
    val name: String, val grams: Double, val per100: Macro, val words: List<String>, val keys: Set<String>,
    val nums: List<Double>,                 // числа в названии: штуки (целые) или объём, л (дробные)
    val pct: List<Double> = emptyList(),    // жирность: «молоко 2,5%»
)

/** Что меню разобрало, и что осталось для библиотеки / магазина / Claude. */
data class MenuResult(val items: List<FoodItem>, val rest: List<String>)

/**
 * Меню сети из ресурса menus/<id>.txt (формат — в шапке файла). Без ИИ и интернета:
 * «кфс твистер острый, фри, кола 0,5» → три позиции с массой порции из меню.
 * Меню включается, если в тексте есть название сети (@chain) или фирменное слово (@sig: твистер, зингер…).
 */
class Menu(
    val source: String, val prefix: String, val chain: Set<String>, val sig: Set<String>, val syn: Map<String, List<String>>,
    val items: List<MenuItem>,
    val always: Boolean = false,                  // @always — таблица работает без названия сети (общие продукты)
    val soft: Set<String> = emptySet(),           // @soft — слова, которые можно не находить в названии (свежий, зелёное…)
    val scale: Map<String, Double> = emptyMap(),  // @scale — размер штуки: большой ×1.3, маленький ×0.7
    val conf: String = "high",
) {

    fun resolve(text: String): MenuResult {
        val chunks = text.split(PARTS).map { it.trim() }.filter { it.isNotEmpty() }
        val all = chunks.flatMap { words(it) }
        if (!always && all.none { it in chain } && all.none { w -> sig.any { hit(w, it) } }) return MenuResult(emptyList(), chunks)
        val items = mutableListOf<FoodItem>(); val rest = mutableListOf<String>()
        for (chunk in chunks) {
            // «тост с сыром и беконом» — одна позиция; «твистер и кола» — две
            val whole = match(chunk)
            if (whole != null) { items += whole; continue }
            for (part in chunk.split(Regex("\\s+и\\s+")).map { it.trim() }.filter { it.isNotEmpty() }) {
                match(part)?.let { items += it } ?: run { if (words(part).any { it !in chain }) rest += part }
            }
        }
        return MenuResult(items, rest)
    }

    /** Одна позиция: все слова запроса есть в позиции; при равенстве — совпал размер, меньше лишних слов, выше в меню. */
    fun match(part0: String): FoodItem? {
        val part = numberWords(part0)
        val all = words(part).filter { it !in chain }
        val k = all.mapNotNull { w -> scale.entries.firstOrNull { hit(w, it.key) }?.value }.fold(1.0) { a, b -> a * b }
        val q = all.filter { w -> scale.keys.none { hit(w, it) } }
        val must = q.filter { w -> soft.none { hit(w, it) } }
        if (must.isEmpty()) return null
        val qn = numbers(part)
        val sizes = qn.filter { it.unit != "g" }.map { it.v }
        val best = items.withIndex()
            .filter { (_, m) -> must.all { w -> m.keys.any { hit(w, it) } } }
            .minWithOrNull(compareBy(
                { (_, m) -> if ((m.nums + m.pct).any { n -> sizes.any { same(it, n) } }) 0 else 1 },
                { (_, m) -> m.words.count { w -> q.none { hit(it, w) } } },
                { (i, _) -> i },
            ))?.value ?: return null
        val grams = qn.firstOrNull { it.unit == "g" }?.v ?: run {
            val left = qn.filter { it.unit != "g" && it.unit != "%" && (best.nums + best.pct).none { n -> same(it.v, n) } }.firstOrNull()
            val pieces = best.nums.firstOrNull { it == Math.floor(it) && it >= 1 }
            val drink = best.nums.any { it != Math.floor(it) && it < 3 }   // в названии объём: «кола 0,5»
            when {
                left == null -> best.grams * k
                left.unit == "l" || (drink && left.v < 3 && left.v != Math.floor(left.v)) -> left.v * 1000  // объём не из меню: 0,33 л
                pieces != null -> best.grams / pieces * left.v                                               // 12 наггетсов
                else -> best.grams * k * left.v                                                              // 2 банана, пол банана
            }
        }
        return FoodItem(if (prefix.isEmpty()) best.name else "$prefix · ${best.name}", Math.round(grams * 10) / 10.0, best.per100, source, conf)
    }

    private data class Num(val v: Double, val unit: String)

    private fun numbers(s: String): List<Num> =
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(мл|л|гр|г|кг|шт|штук|x|х|%)?(?!\\p{L})").findAll(s.lowercase()).map {
            val v = it.groupValues[1].replace(',', '.').toDouble()
            when (it.groupValues[2]) {
                "мл" -> Num(v / 1000, "l"); "л" -> Num(v, "l"); "г", "гр" -> Num(v, "g"); "кг" -> Num(v * 1000, "g"); "%" -> Num(v, "%")
                else -> Num(v, "")
            }
        }.toList()

    private fun words(s: String): List<String> = tokens(s).flatMap { syn[it] ?: listOf(it) }.filter { it !in STOP }

    companion object {
        private val STOP = setOf("из", "в", "во", "с", "со", "и", "на", "по", "шт", "штук", "штуки", "порция", "порции", "л", "мл", "г", "гр", "x", "х")

        private val NUMBER_WORDS = listOf(
            "одну|одна|одно|один" to "1", "две|два|пару|пара" to "2", "три" to "3", "четыре" to "4", "пять" to "5",
            "шесть" to "6", "семь" to "7", "восемь" to "8", "девять" to "9", "десять" to "10",
            "полтора|полторы" to "1.5", "половину|половина|половинка|пол" to "0.5",
        )

        /** «один банан» → «1 банан», «пол банана» → «0.5 банана». */
        fun numberWords(s: String): String = NUMBER_WORDS.fold(s.lowercase().replace('ё', 'е')) { acc, (w, n) ->
            acc.replace(Regex("(?<!\\p{L})(?:$w)(?!\\p{L})"), " $n ").replace(Regex("(?<!\\p{L})пол(?=[бвгджзклмнпрстфхцчшщ]\\p{L}{2,})"), "0.5 ")
        }

        fun tokens(s: String) = Regex("\\p{L}+").findAll(s.lowercase().replace('ё', 'е')).map { it.value }.toList()

        private fun same(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-6

        /** Слово запроса совпадает со словом меню: точно (короткие) или по общему началу (крылья ~ крылышки, острая ~ острый). */
        fun hit(q: String, w: String): Boolean {
            if (q == w) return true
            val n = minOf(q.length, w.length)
            if (n < 3) return false
            val common = q.zip(w).takeWhile { (a, b) -> a == b }.size
            return common >= maxOf(3, n - 2)
        }

        fun parse(text: String): Menu {
            var source = ""; var prefix = ""; val chain = mutableSetOf<String>(); val sig = mutableSetOf<String>()
            var always = false; var conf = "high"; val soft = mutableSetOf<String>(); val scale = mutableMapOf<String, Double>()
            val syn = mutableMapOf<String, List<String>>(); val items = mutableListOf<MenuItem>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("@")) {
                    val key = line.substringBefore(' '); val v = line.substringAfter(' ').trim()
                    when (key) {
                        "@source" -> source = v
                        "@prefix" -> prefix = v
                        "@chain" -> chain += tokens(v)
                        "@sig" -> sig += tokens(v)
                        "@syn" -> tokens(v).let { syn[it.first()] = it.drop(1) }
                        "@always" -> always = true
                        "@conf" -> conf = v
                        "@soft" -> soft += tokens(v)
                        "@scale" -> v.split(Regex("\\s+")).let { (w, x) -> scale[w.lowercase().replace('ё', 'е')] = x.toDouble() }
                    }
                    continue
                }
                val c = line.split('|').map { it.trim() }
                fun d(i: Int) = c[i].replace(',', '.').toDouble()
                val name = c[0]
                val words = tokens(name).filter { it !in STOP }
                val keys = (words + c.getOrElse(6) { "" }.let(::tokens)).filter { it !in STOP }.toSet()
                val all = Regex("(\\d+(?:[.,]\\d+)?)\\s*(%?)").findAll(name).map { it.groupValues[1].replace(',', '.').toDouble() to it.groupValues[2] }.toList()
                items += MenuItem(name, d(1), Macro(d(5), d(2), d(3), d(4)), words, keys,
                    all.filter { it.second.isEmpty() }.map { it.first }, all.filter { it.second == "%" }.map { it.first })
            }
            return Menu(source, prefix, chain, sig, syn, items, always, soft, scale, conf)
        }

        fun load(id: String): Menu = parse(Menu::class.java.getResource("/menus/$id.txt")!!.readText())
    }
}

/** Все меню сетей. Новая сеть — новый файл в resources/menus и строка здесь. */
object Menus {
    val all: List<Menu> by lazy { listOf("kfc", "kinza").map(Menu::load) }

    /** Общие продукты (банан, гречка, яйца…) — средние значения из таблиц калорийности и вес штуки. */
    val basic: Menu by lazy { Menu.load("basic") }

    /** Прогоняет текст через все меню по очереди; что не нашлось ни в одном — в rest. */
    fun resolve(text: String): MenuResult {
        var rest = listOf(text); val items = mutableListOf<FoodItem>()
        for (m in all) {
            if (rest.isEmpty()) break
            val r = m.resolve(rest.joinToString(", "))
            items += r.items; rest = r.rest
        }
        return MenuResult(items, rest)
    }
}
