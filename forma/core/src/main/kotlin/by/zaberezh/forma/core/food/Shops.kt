package by.zaberezh.forma.core.food

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Товар из поиска магазина; per100 — если магазин сразу отдал КБЖУ (edostavka отдаёт их прямо в выдаче). */
data class ShopItem(
    val shop: String,
    val id: String,
    val name: String,
    val url: String,
    val per100: Macro? = null,
    val packGrams: Double? = null,
    val pieceGrams: Double? = null,
)

/** Магазин: поиск по названию (в порядке самого магазина) и КБЖУ товара, если поиск их не отдал. */
interface Shop {
    val title: String
    fun search(query: String): List<ShopItem>
    fun details(item: ShopItem): ShopItem? = item.takeIf { it.per100 != null }
}

/**
 * Похож ли товар магазина на то, что написал человек. Слова сравниваются по основе (банана ~ банан, детский ~ детское)
 * и между алфавитами и написаниями (Lotte ~ лотте, Choco Pie ~ чокопай, Snickers ~ сникерс).
 * Счёт — доля слов запроса, нашедшихся в названии; первое слово (обычно — что это: кефир, печенье, сыр) весит больше,
 * и без него нужен почти полный совпавший остаток: «молоко детское депи» — не «кефир детский депи».
 */
object ShopMatch {
    /** Связки, количества и единицы — не про сам продукт. */
    private val FILLER = setOf(
        "с", "со", "и", "в", "во", "на", "для", "из", "по", "от", "к", "а", "или",
        "вкус", "вкусом", "аромат", "ароматом", "ароматизатор",
        "шт", "штук", "штуки", "штука", "г", "гр", "грамм", "граммов", "грамма", "кг", "мл", "л", "литр", "литра",
        "уп", "упаковка", "упаковки", "упаковку", "пачка", "пачки", "пачку", "порция", "порции", "порцию",
        "съел", "съела", "выпил", "выпила", "немного", "один", "одна", "одно", "одну", "два", "две", "три", "половина", "пол",
    )
    private val JOIN = setOf("с", "со")
    const val ACCEPT = 0.65
    /** Без первого слова запроса — только почти полное совпадение остального (Пирожное Lotte Choco Pie для «печенье Lotte Choco Pie»). */
    const val ACCEPT_WITHOUT_FIRST = 0.7

    fun words(s: String): List<String> = Regex("\\p{L}+").findAll(s.lowercase().replace('ё', 'е')).map { it.value }.toList()
    fun queryWords(s: String): List<String> = words(s).filter { it !in FILLER }

    private val TR = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s",
        'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ы' to "y",
        'э' to "e", 'ю' to "yu", 'я' to "ya", 'ь' to "", 'ъ' to "", 'і' to "i", 'ў' to "u")

    private fun cyr(w: String) = w.any { it in 'а'..'я' || it == 'і' || it == 'ў' }
    fun latin(w: String) = if (cyr(w)) w.map { TR[it] ?: it.toString() }.joinToString("") else w

    /** Звучание латиницей, без разницы в написании: choco = чоко, lotte = лотте, snickers = сникерс, twix = твикс. */
    fun sound(w: String): String {
        val s = latin(w).replace("sch", "š").replace("ch", "č").replace("sh", "š").replace("zh", "ž").replace("kh", "h")
            .replace("ck", "k").replace("ph", "f").replace("qu", "kv").replace('q', 'k').replace('c', 'k')
            .replace('w', 'v').replace('y', 'i').replace('j', 'i').replace("x", "ks")
        return s.replace(Regex("(.)\\1+"), "$1")   // двойные — одной: lotte → lote
    }

    /** Латинские слова — кириллицей, как пишут «Соседи»: lotte → лотте, choco → чоко. */
    fun cyrillic(w: String): String {
        if (cyr(w)) return w
        var s = w
        for ((a, b) in LAT2) s = s.replace(a, b)
        return s.map { LAT1[it] ?: it.toString() }.joinToString("")
    }
    private val LAT2 = listOf("sch" to "щ", "sh" to "ш", "ch" to "ч", "zh" to "ж", "kh" to "х", "ts" to "ц", "ya" to "я", "yu" to "ю",
        "ck" to "к", "ph" to "ф", "th" to "т", "oo" to "у", "ee" to "и", "qu" to "кв")
    private val LAT1 = mapOf('a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г", 'h' to "х", 'i' to "и",
        'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п", 'q' to "к", 'r' to "р", 's' to "с", 't' to "т",
        'u' to "у", 'v' to "в", 'w' to "в", 'x' to "кс", 'y' to "и", 'z' to "з")

    /** Основа: короткие слова — целиком, средние — первые 4 буквы, длинные — без двух последних (окончание). */
    private fun stem(w: String) = when { w.length <= 4 -> w; w.length <= 6 -> w.take(4); else -> w.take(w.length - 2) }
    private fun skeleton(s: String) = s.filter { it !in "aeiou" }

    /** Одно слово запроса и одно слово названия — об одном и том же. */
    fun same(q: String, t: String): Boolean {
        if (q == t) return true
        if (cyr(q) == cyr(t)) return q.length >= 4 && t.length >= 4 && (t.startsWith(stem(q)) || q.startsWith(stem(t)))
        val a = sound(q); val b = sound(t)
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && (b.startsWith(stem(a)) || a.startsWith(stem(b)))) return true
        val sa = skeleton(a)
        return sa.length >= 3 && sa == skeleton(b)            // bounty ~ баунти
    }

    /** Насколько товар похож на запрос: score 0…1, первое слово нашлось, лишних слов в названии. */
    data class Fit(val score: Double, val first: Boolean, val extra: Int) {
        val ok get() = score >= ACCEPT && (first || score >= ACCEPT_WITHOUT_FIRST)
    }

    /** Жирность и подобное: «5%», «3,2 %». */
    private fun percents(s: String) = Regex("(\\d+(?:[.,]\\d+)?)\\s*%").findAll(s).mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }.toSet()

    fun fit(query: String, title: String): Fit {
        val q = queryWords(query)
        if (q.isEmpty()) return Fit(0.0, false, 0)
        // «творог 5%» — это не «творог 2%»: жирность из запроса — ещё одно «слово»
        val pq = percents(query)
        val pctHit = pq.isNotEmpty() && percents(title).any { it in pq }
        val t = words(title).filter { it !in FILLER }
        val units = t + t.zipWithNext { a, b -> a + b }       // «чоко пай» в названии ~ «чокопай» в запросе
        val hit = BooleanArray(q.size) { i -> units.any { same(q[i], it) } }
        for (i in 0 until q.size - 1) if ((!hit[i] || !hit[i + 1]) && cyr(q[i]) == cyr(q[i + 1])) {
            val joined = q[i] + q[i + 1]                       // «choco pie» в запросе ~ «чокопай» в названии
            // склейка засчитывается, только если совпала целиком, а не одним первым словом
            if (units.any { it.length >= joined.length - 2 && same(joined, it) }) { hit[i] = true; hit[i + 1] = true }
        }
        val w = DoubleArray(q.size) { if (it == 0) 1.5 else 1.0 }
        val pctW = if (pq.isEmpty()) 0.0 else 1.0
        val score = (q.indices.sumOf { if (hit[it]) w[it] else 0.0 } + if (pctHit) pctW else 0.0) / (w.sum() + pctW)
        val joined = q.zipWithNext { a, b -> a + b }.filter { j -> t.any { it.length >= j.length - 2 } }
        val asked = words(query)
        // лишнее в названии: чужие слова, а «с ветчиной», «с соусом» — уже блюдо, а не сам продукт
        val extra = t.count { tw -> tw.length >= 3 && q.none { same(it, tw) } && joined.none { same(it, tw) } } +
            words(title).count { it in JOIN && it !in asked }
        return Fit(score, hit[0], extra)
    }

    fun score(query: String, title: String) = fit(query, title).score
}

/** Масса одной штуки из названия: «6 шт*30 г», «4 шт. по 28 г», «30 г х 6 шт», «6х30г». */
fun pieceGrams(title: String): Double? {
    val t = title.lowercase().replace(',', '.')
    val n = "(\\d+(?:\\.\\d+)?)"
    val unit = "(?:г|гр|мл)(?![а-я])"
    val patterns = listOf(
        Regex("\\d+\\s*шт\\.?\\s*(?:[*×xх]|по)\\s*$n\\s*$unit"),
        Regex("$n\\s*$unit\\s*[*×xх]\\s*\\d+\\s*шт"),
        Regex("(?<![\\d.])\\d{1,2}\\s*[*×xх]\\s*$n\\s*$unit"),
    )
    return patterns.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1)?.toDoubleOrNull() }?.takeIf { it in 1.0..1000.0 }
}

/**
 * «Соседи» (sosedi-dostavka.by): открытый API их сайта — поиск и карточка товара с КБЖУ на 100 г.
 * Поиск у них широкий (на «кефир депи» — сотня товаров), поэтому берём первые 40 и выбираем сами.
 */
class Sosedi(private val fetch: (String) -> String?, private val log: (String) -> Unit = {}) : Shop {
    override val title = "sosedi-dostavka.by"

    override fun search(query: String): List<ShopItem> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val raw = fetch("$API/v2/products/search?query=$q") ?: return emptyList<ShopItem>().also { log("sosedi: нет ответа") }
        val data = (Json.parseToJsonElement(raw) as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return data.take(40).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val name = o.str("name").orEmpty().replace(Regex("^\\d{4,14}\\s+"), "").trim()
            ShopItem(title, id, name, SITE, packGrams = Edostavka.packGrams(name), pieceGrams = pieceGrams(name))
        }.also { log("sosedi «$query»: товаров ${it.size}") }
    }

    override fun details(item: ShopItem): ShopItem? {
        val o = Json.parseToJsonElement(fetch("$API/products/${item.id}/$DARKSTORE") ?: return null) as? JsonObject ?: return null
        fun num(k: String) = o.str(k)?.replace(',', '.')?.toDoubleOrNull()
        val name = o.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: item.name
        val m = WebFood.labelMacro(num("calorie"), num("protein"), num("fat"), num("carbohydrate"), name) ?: return null
        // weight у них в кг или л: «0.95»
        val pack = Edostavka.packGrams(name) ?: item.packGrams ?: num("weight")?.takeIf { it in 0.005..10.0 }?.times(1000)
        return item.copy(name = name, per100 = m, url = o.str("slug")?.let { "$SITE/products/$it" } ?: item.url,
            packGrams = pack, pieceGrams = pieceGrams(name) ?: item.pieceGrams)
    }

    companion object {
        private const val API = "https://dev.bazar-store.by"
        private const val SITE = "https://sosedi-dostavka.by"
        private const val DARKSTORE = 10   // склад «Соседей» в Минске; КБЖУ у товара одинаковое на любом
    }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

/**
 * Магазинный товар по названию: ищем во всех магазинах сразу, берём самый похожий товар с КБЖУ.
 * Длинный запрос магазины ищут плохо (edostavka хочет почти все слова, «Соседи» латиницу не знают),
 * поэтому, пока уверенного совпадения нет, пробуем короче: кириллицей, без первого слова, только бренд.
 */
class ShopFinder(private val shops: List<Shop>, private val log: (String) -> Unit = {}) {
    data class Hit(val shop: Shop, val item: ShopItem, val fit: ShopMatch.Fit, val rank: Int)

    fun variants(part: String): List<String> {
        val w = ShopMatch.queryWords(Edostavka.cleanQuery(part))
        if (w.isEmpty()) return emptyList()
        val latin = w.filter { it.none { c -> c in 'а'..'я' } }
        val out = linkedSetOf(w.joinToString(" "))
        if (latin.isNotEmpty()) out += w.joinToString(" ") { ShopMatch.cyrillic(it) }
        if (w.size >= 3) out += w.drop(1).joinToString(" ")
        if (latin.isNotEmpty() && latin.size < w.size) out += latin.joinToString(" ")
        if (w.size >= 3) out += w.take(2).joinToString(" ")
        return out.take(4)
    }

    /** Все товары из всех магазинов, похожие на запрос, — лучшие первыми. all — пройти все варианты запроса. */
    fun candidates(part: String, all: Boolean = false): List<Hit> {
        val vs = variants(part)
        if (vs.isEmpty() || shops.isEmpty()) return emptyList()
        val futures = shops.map { shop -> POOL.submit(Callable { searchShop(shop, part, vs, all) }) }
        val all = futures.flatMap { f -> runCatching { f.get(30, TimeUnit.SECONDS) }.getOrElse { log("магазин: ${it.message}"); emptyList() } }
        return all.sortedWith(compareByDescending<Hit> { it.fit.score }.thenBy { it.fit.extra }.thenBy { it.rank }.thenBy { shops.indexOf(it.shop) })
            .distinctBy { it.shop.title + "/" + it.item.id }
    }

    private fun searchShop(shop: Shop, query: String, vs: List<String>, all: Boolean): List<Hit> {
        val out = mutableListOf<Hit>()
        vs.forEachIndexed { vi, q ->
            val items = runCatching { shop.search(q) }.onFailure { log("${shop.title}: ${it.message}") }.getOrDefault(emptyList())
            items.forEachIndexed { i, it -> out += Hit(shop, it, ShopMatch.fit(query, it.name), vi * 100 + i) }
            // похожее уже есть — короче не ищем: меньше запросов к магазину (у «Соседей» частые запросы тормозят)
            if (!all && out.any { it.fit.ok }) return out
        }
        return out
    }

    /**
     * Лучший товар с КБЖУ для одной позиции; null — похожего нет.
     * Если у найденного товара в карточке нет цифр (у «Соседей» бывает) — тот же продукт другого бренда:
     * «Lotte Choco Pie банан» → «Чоко Пай» (с пометкой «похожий товар», уверенность ниже).
     */
    fun find(part: String, hits: List<Hit> = candidates(part)): FoodItem? {
        val tried = mutableSetOf<String>()
        take(part, hits, tried)?.let { return it }
        val latin = ShopMatch.queryWords(Edostavka.cleanQuery(part)).filter { w -> w.none { it in 'а'..'я' } }
        if (latin.size < 2) return null
        val core = (if (latin.size >= 3) latin.drop(1) else latin).joinToString(" ")   // без бренда: lotte choco pie → choco pie
        return take(part, candidates(core, all = true), tried, core)?.let { it.copy(source = it.source.substringBefore(" ·") + " · похожий товар" +
            it.source.substringAfter(" ·", "").let { n -> if (n.isEmpty()) "" else " ·$n" }, conf = "medium") }
    }

    private fun take(part: String, hits: List<Hit>, tried: MutableSet<String>, label: String = part): FoodItem? {
        for (h in hits.filter { it.fit.ok && tried.add(it.shop.title + "/" + it.item.id) }.take(4)) {
            val full = runCatching { h.shop.details(h.item) }.onFailure { log("${h.shop.title}: ${it.message}") }.getOrNull() ?: continue
            val m = full.per100 ?: continue
            val (grams, note) = gramsFor(part, full)
            log("магазин: «$label» → ${full.shop} «${full.name}» (совпадение ${(h.fit.score * 100).toInt()}%)")
            return FoodItem(full.name, grams, m, full.shop + (note?.let { " · $it" } ?: ""), "high")
        }
        log("магазин: «$label» — у похожих товаров нет КБЖУ в карточке")
        return null
    }

    fun resolve(parts: List<String>): ShopResult {
        val items = mutableListOf<FoodItem>(); val left = mutableListOf<String>(); val hints = mutableListOf<ShopPage>()
        for (part in parts) {
            val words = Edostavka.cleanQuery(part).split(" ").filter { it.length >= 3 }
            if (words.isEmpty() || words.all { it in GENERIC }) { left += part; continue }
            val hits = candidates(part)
            val found = find(part, hits)
            if (found != null) { items += found; continue }
            left += part
            // похожие, но не точно — подсказка для Claude
            hints += hits.filter { it.fit.score > 0 }.take(2).map { h ->
                ShopPage(h.item.url, h.item.name, h.item.per100?.let { "на 100 г: ${it.kcal} ккал, Б ${it.p}, Ж ${it.f}, У ${it.c}" }.orEmpty(),
                    h.item.per100, h.item.packGrams)
            }
        }
        return ShopResult(items, left, hints)
    }

    companion object {
        /**
         * Сколько класть, если вес не написан: штука, маленькая упаковка целиком (йогурт, сырок, бутылка напитка до 0,5 л),
         * порция соуса — иначе 100 г и просьба поправить (пачка печенья 336 г — это не одна порция).
         */
        fun gramsFor(part: String, item: ShopItem): Pair<Double, String?> {
            WebFood.gramsOf(part)?.takeIf { it > 0 }?.let { return it to null }
            val name = item.name.lowercase()
            val drink = Regex("\\d\\s*(?:мл|л)(?![а-я])").containsMatchIn(name) || DRINK.containsMatchIn(name)
            val pack = item.packGrams?.takeIf { it <= if (drink) 500.0 else 250.0 }   // маленькая упаковка = одна порция
            val unit = item.pieceGrams ?: pack
            val count = COUNT.find(part.lowercase())?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            if (count != null) return if (unit != null) unit * count to null else 100.0 * count to "вес штуки не указан — стоит 100 г, поправь"
            (condimentPortion(part) ?: condimentPortion(item.name))?.let { return it to null }
            item.pieceGrams?.let { return it to "1 шт. — поправь, если больше" }
            pack?.let { return it to "вся упаковка — поправь, если меньше" }
            return 100.0 to "вес не указан — стоит 100 г, поправь"
        }

        private val DRINK = Regex("напит|коктейл|кефир|молоко|йогурт питьев|ряженк|айран|тан\\b|сок\\b|нектар|морс|вода|лимонад|квас|чай|кофе|энергетик|кола|пепси|спрайт|фанта")

        private val POOL = Executors.newCachedThreadPool { r -> Thread(r, "shops").apply { isDaemon = true } }
    }
}
