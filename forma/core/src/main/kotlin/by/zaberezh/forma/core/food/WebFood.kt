package by.zaberezh.forma.core.food

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * КБЖУ из интернета без ИИ: поисковик → сниппеты и страницы сайтов калорийности → разбор чисел на телефоне.
 * Берётся медиана по найденным вариантам, у которых калории сходятся с Б/Ж/У (отсекает мусор и «на порцию»).
 */
class WebFood(private val fetch: (String) -> String? = Edostavka::httpGet, private val log: (String) -> Unit = {}) {

    data class Found(val per100: Macro, val from: String)

    fun find(part: String): FoodItem? {
        val name = nameOf(part)
        if (name.isBlank()) return null
        val f = lookup(name) ?: return null
        val grams = gramsOf(part)
        val note = if (grams == null) " · вес не указан — стоит 100 г, поправь" else ""
        return FoodItem(name.replaceFirstChar { it.uppercase() }, grams ?: 100.0, f.per100, "интернет: ${f.from}$note", "medium")
    }

    /**
     * Порядок: Open Food Facts (открытая база продуктов, JSON) → поиск по сайту calorizator.ru → поисковики.
     * Магазины (edostavka, «Соседи») ищутся раньше, отдельным шагом — см. [ShopFinder].
     */
    fun lookup(name: String): Found? =
        runCatching { openFoodFacts(name) }.onFailure { log("openfoodfacts: ${it.message}") }.getOrNull()
            ?: runCatching { calorizator(name) }.onFailure { log("calorizator: ${it.message}") }.getOrNull()
            ?: searchEngines(name)

    /** Open Food Facts: товары по запросу, у которых название похоже на запрос; берётся медиана по калориям. */
    fun openFoodFacts(name: String): Found? {
        val q = URLEncoder.encode(name, "UTF-8")
        val json = fetch("https://world.openfoodfacts.org/cgi/search.pl?search_terms=$q&search_simple=1&action=process&json=1&page_size=24" +
            "&fields=product_name,product_name_ru,nutriments") ?: return null.also { log("openfoodfacts: нет ответа") }
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject ?: return null
        val stems = Menu.tokens(name).filter { it.length >= 3 }.map { it.take(4) }
        val pool = (root["products"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val title = str("product_name_ru").ifBlank { str("product_name") }
            val words = Menu.tokens(title)
            if (stems.isNotEmpty() && stems.none { s -> words.any { it.startsWith(s) } }) return@mapNotNull null
            val n = o["nutriments"] as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun num(k: String) = (n[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
            val p = num("proteins_100g") ?: return@mapNotNull null
            val f = num("fat_100g") ?: return@mapNotNull null
            val c = num("carbohydrates_100g") ?: return@mapNotNull null
            val kcal = num("energy-kcal_100g") ?: (p * 4 + f * 9 + c * 4)
            Macro(kcal, p, f, c, num("fiber_100g") ?: 0.0).takeIf(::plausible)
        }
        log("openfoodfacts: подходящих ${pool.size}")
        if (pool.isEmpty()) return null
        val median = pool.map { it.kcal }.sorted()[pool.size / 2]
        return Found(pool.minBy { kotlin.math.abs(it.kcal - median) }, "Open Food Facts")
    }

    /** Поиск по сайту calorizator.ru → страницы продуктов (таблица «на 100 г»). */
    fun calorizator(name: String): Found? {
        val html = fetch("https://calorizator.ru/search/node/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")) ?: return null
        val links = Regex("(?:https?://(?:www\\.)?calorizator\\.ru)?(/product/[^\"'#?\\s<>]+)").findAll(html)
            .map { "https://calorizator.ru" + it.groupValues[1] }.distinct().take(3).toList()
        log("calorizator: страниц ${links.size}")
        for (url in links) {
            val page = fetch(url) ?: continue
            macrosIn(Edostavka.textOf(page), maxOf = 1).firstOrNull()?.let { return Found(it, "calorizator.ru") }
        }
        return null
    }

    fun searchEngines(name: String): Found? {
        val q = URLEncoder.encode("$name калорийность на 100 грамм белки жиры углеводы", "UTF-8")
        val found = mutableListOf<Found>()
        for (src in listOf("https://html.duckduckgo.com/html/?q=$q", "https://www.bing.com/search?q=$q&setlang=ru", "https://lite.duckduckgo.com/lite/?q=$q")) {
            val html = fetch(src) ?: continue
            val engine = src.substringAfter("//").substringBefore('/')
            found += macrosIn(Edostavka.textOf(html), maxOf = 4).map { Found(it, "выдача $engine") }
            // страницы известных сайтов калорийности — там таблица «на 100 г»
            val decoded = runCatching { URLDecoder.decode(html, "UTF-8") }.getOrDefault(html)
            SITE.findAll(decoded).map { it.value.trimEnd('.', ',', ')') }.distinct().take(2).forEach { url ->
                val page = fetch(url) ?: return@forEach
                macrosIn(Edostavka.textOf(page), maxOf = 2).firstOrNull()?.let { found += Found(it, url.substringAfter("//").substringBefore('/')) }
            }
            log("$engine: вариантов ${found.size}")
            if (found.count { it.from.startsWith("выдача").not() } >= 1 || found.size >= 3) break
        }
        if (found.isEmpty()) return null
        // приоритет страницам сайтов; среди всех — ближайший к медиане по калориям
        val pool = found.filter { !it.from.startsWith("выдача") }.ifEmpty { found }
        val median = pool.map { it.per100.kcal }.sorted()[pool.size / 2]
        return pool.minBy { kotlin.math.abs(it.per100.kcal - median) }
    }

    companion object {
        private val SITE = Regex("https?://(?:www\\.)?(?:calorizator\\.ru|tablicakalorijnosti\\.ru|fatsecret\\.ru|health-diet\\.ru|edaplus\\.info|dietadiary\\.com|frykt\\.ru)/[^\"'&\\s<>]+")
        private const val N = "(\\d+(?:[.,]\\d+)?)"

        private fun d(s: String) = s.replace(',', '.').toDouble()


        /**
         * КБЖУ с этикетки магазина. В поле калорий бывают и ккал, и кДж (кефир 1,5%: 174.5 — это кДж):
         * верим тому варианту, что сходится с 4·Б + 9·Ж + 4·У; не сходится ни один — считаем по БЖУ.
         * Пустое поле (бывает у части товаров) считается нулём, только если калории с этим сходятся.
         * Пустые жиры при жирности в названии — не ноль, а незаполненная карточка: у «творога 9%» без жиров
         * «сходятся» неверные 70 ккал, а у кваса жиров и правда нет.
         */
        fun labelMacro(cal: Double?, p: Double?, f: Double?, c: Double?, name: String = ""): Macro? {
            if (f == null && Regex("(\\d+(?:[.,]\\d+)?)\\s*%").findAll(name).any { (it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) > 0.4 }) return null
            val full = p != null && f != null && c != null
            val pp = p ?: 0.0; val ff = f ?: 0.0; val cc = c ?: 0.0
            val calc = pp * 4 + ff * 9 + cc * 4
            val kcal = listOfNotNull(cal, cal?.div(4.184)).firstOrNull { it > 0 && kotlin.math.abs(it - calc) <= 0.25 * it }
                ?: calc.takeIf { full } ?: return null
            return Macro(kcal, pp, ff, cc).takeIf { calc > 0 && plausible(it) }
        }

        /** Калории сходятся с 4·Б + 9·Ж + 4·У (±25%) и правдоподобны для 100 г. */
        fun plausible(m: Macro): Boolean {
            if (m.kcal !in 0.0..950.0 || m.p > 100 || m.f > 100 || m.c > 100 || m.p + m.f + m.c > 101) return false
            val calc = m.p * 4 + m.f * 9 + m.c * 4
            return if (m.kcal < 5) calc < 8 else kotlin.math.abs(calc - m.kcal) / m.kcal < 0.25
        }

        /** Все правдоподобные наборы КБЖУ в тексте: окна вокруг «ккал», форматы «Белки 1,5», «Б/Ж/У 1.5/0.2/21.8», «Б: 1.5». */
        fun macrosIn(text: String, maxOf: Int = 4): List<Macro> {
            val out = mutableListOf<Macro>()
            for (m in Regex("ккал|kcal", RegexOption.IGNORE_CASE).findAll(text)) {
                val w = text.substring((m.range.first - 220).coerceAtLeast(0), (m.range.last + 220).coerceAtMost(text.length))
                (parse(w) ?: continue).takeIf(::plausible)?.let { if (out.none { o -> o == it }) out += it }
                if (out.size >= maxOf) break
            }
            return out
        }

        /** КБЖУ из куска текста или null. */
        fun parse(w: String): Macro? {
            val kcal = Regex("$N\\s*(?:ккал|kcal)", RegexOption.IGNORE_CASE).find(w)?.groupValues?.get(1)?.let(::d)
                ?: Regex("(?:калорийность|энергетическая ценность)[^\\d]{0,25}$N", RegexOption.IGNORE_CASE).find(w)?.groupValues?.get(1)?.let(::d)
            Regex("Б\\s*/\\s*Ж\\s*/\\s*У[^\\d]{0,12}$N\\s*/\\s*$N\\s*/\\s*$N", RegexOption.IGNORE_CASE).find(w)?.let { r ->
                val (p, f, c) = r.destructured
                return Macro(kcal ?: (d(p) * 4 + d(f) * 9 + d(c) * 4), d(p), d(f), d(c))
            }
            Edostavka.parseMacros(w)?.let { m -> if (kcal != null && plausible(m.copy(kcal = kcal))) return m.copy(kcal = kcal); if (plausible(m)) return m }
            fun abbr(l: String) = Regex("(?<!\\p{L})$l\\s*[:\\-–—]?\\s*$N", RegexOption.IGNORE_CASE).find(w)?.groupValues?.get(1)?.let(::d)
            val p = abbr("Б") ?: return null; val f = abbr("Ж") ?: return null; val c = abbr("У") ?: return null
            return Macro(kcal ?: (p * 4 + f * 9 + c * 4), p, f, c)
        }

        private val FILLER = setOf("съел", "съела", "выпил", "выпила", "порция", "порцию", "штук", "штуки", "шт", "грамм", "граммов", "г", "гр", "кг", "мл", "л")

        /** Название без количеств: «2 сникерса 50г» → «сникерса». */
        fun nameOf(part: String): String = Edostavka.cleanQuery(Menu.numberWords(part)).split(" ")
            .filter { it.isNotBlank() && it !in FILLER && it.toDoubleOrNull() == null }.joinToString(" ")

        fun gramsOf(part: String): Double? {
            val m = Regex("$N\\s*(кг|гр|г|грамм\\p{L}*|мл|л)(?!\\p{L})", RegexOption.IGNORE_CASE).find(part.lowercase()) ?: return null
            val v = d(m.groupValues[1])
            return when (m.groupValues[2]) { "кг", "л" -> v * 1000; else -> v }
        }
    }
}
