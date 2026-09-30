package by.zaberezh.forma.core.food

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Страница товара: название (с массой упаковки), кусок текста с КБЖУ и что удалось разобрать. */
data class ShopPage(val url: String, val title: String, val snippet: String, val per100: Macro?, val packGrams: Double?)

/**
 * Точные КБЖУ магазинных продуктов с edostavka.by — запросы идут прямо с телефона, без ИИ и токенов.
 * Поиск: edostavka.by/search?query=…, товар: edostavka.by/product/<id>, КБЖУ — блок «На 100 грамм».
 */
class Edostavka(private val fetch: (String) -> String? = ::httpGet, private val log: (String) -> Unit = {}) {

    /** Найти товар под одну позицию. null — не нашли или название не совпало. */
    fun lookup(query: String): ShopPage? = candidates(query).firstOrNull { matches(query, it.title) }

    /** Страницы по запросу (до 3), даже если КБЖУ не разобрались — пригодятся Claude как контекст. */
    fun candidates(query: String): List<ShopPage> {
        val q = cleanQuery(query)
        if (q.isBlank()) return emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")
        val site = URLEncoder.encode("site:edostavka.by $q", "UTF-8")
        // сам сайт; если результаты рисуются скриптом — ссылки через поисковики
        val sources = listOf(
            "https://edostavka.by/search?query=$enc",
            "https://html.duckduckgo.com/html/?q=$site",
            "https://www.bing.com/search?q=$site",
        )
        var ids = emptyList<String>()
        for (src in sources) {
            val html = fetch(src)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) } ?: continue
            ids = Regex("(?:edostavka\\.by)?/product/(\\d{4,})").findAll(html).map { it.groupValues[1] }.distinct().take(3).toList()
            log("поиск ${src.substringBefore("?")}: товаров ${ids.size}")
            if (ids.isNotEmpty()) break
        }
        return ids.mapNotNull { id -> page("https://edostavka.by/product/$id") }
            .sortedByDescending { score(q, it.title) }
    }

    fun page(url: String): ShopPage? {
        val html = fetch(url) ?: return null.also { log("страница не открылась: $url") }
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?.let(::decode)?.substringBefore(" купить")?.trim().orEmpty()
        val text = textOf(html)
        val at = listOf("На 100 г", "на 100 г", "100 грамм", "Белки").map { text.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val snippet = if (at == null) "" else text.substring(at, minOf(text.length, at + 260))
        return ShopPage(url, title, snippet, parseMacros(snippet), packGrams(title)).also {
            log("$url · «$title» · КБЖУ ${it.per100?.let { m -> "${m.kcal}/${m.p}/${m.f}/${m.c}" } ?: "не разобраны: ${snippet.take(120)}"}")
        }
    }

    companion object {
        private const val NUM = "(\\d+(?:[.,]\\d+)?)"

        /**
         * КБЖУ из текста блока «На 100 грамм». Порядок на сайте может быть «9 Белки» или «Белки 9» —
         * разбираем оба варианта и берём тот, где калории сходятся с 4·Б + 9·Ж + 4·У.
         */
        fun parseMacros(snippet: String): Macro? {
            if (snippet.isBlank()) return null
            fun num(m: MatchResult?) = m?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            fun before(w: String) = num(Regex("$NUM\\s*(?:г\\s*)?$w", RegexOption.IGNORE_CASE).find(snippet))
            fun after(w: String) = num(Regex("$w[\\p{L}]*\\s*[:\\-–]?\\s*$NUM", RegexOption.IGNORE_CASE).find(snippet))
            val kcal = num(Regex("$NUM\\s*ккал", RegexOption.IGNORE_CASE).find(snippet))
                ?: num(Regex("ккал\\s*[:\\-–]?\\s*$NUM", RegexOption.IGNORE_CASE).find(snippet))
            val variants = listOf(::before, ::after).mapNotNull { g ->
                val p = g("Белк"); val f = g("Жир"); val c = g("Углевод")
                if (p == null || f == null || c == null) null else Macro(kcal ?: (p * 4 + f * 9 + c * 4), p, f, c)
            }
            fun fits(m: Macro): Boolean { val calc = m.p * 4 + m.f * 9 + m.c * 4; return calc == 0.0 || kotlin.math.abs(m.kcal - calc) / calc < 0.3 }
            return variants.firstOrNull(::fits) ?: variants.firstOrNull()
        }

        /** Масса упаковки из названия: «330 г», «0.25 кг», «1 л». */
        fun packGrams(title: String): Double? {
            val m = Regex("$NUM\\s*(кг|г|гр|мл|л)(?![а-я])", RegexOption.IGNORE_CASE).findAll(title).lastOrNull() ?: return null
            val v = m.groupValues[1].replace(',', '.').toDouble()
            return when (m.groupValues[2].lowercase()) { "кг", "л" -> v * 1000; else -> v }
        }

        private val STOP = setOf("г", "гр", "мл", "шт", "и", "с", "со", "вкус", "на", "из", "в", "без", "кг", "л")

        /** Запрос без количеств: «2 теос про клубника 330г» → «теос про клубника». */
        fun cleanQuery(q: String): String = q.lowercase().replace('ё', 'е')
            .replace(Regex("\\d+(?:[.,]\\d+)?\\s*(?:г|гр|грамм\\w*|мл|шт\\.?|x|х|кг|л)?(?=\\s|$)"), " ")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

        private val TR = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
            'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s",
            'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ы' to "y",
            'э' to "e", 'ю' to "yu", 'я' to "ya", 'ь' to "", 'ъ' to "")

        private fun translit(w: String) = w.map { TR[it] ?: it.toString() }.joinToString("")

        private fun words(q: String) = cleanQuery(q).split(" ").filter { it.length >= 2 && it !in STOP }

        /** Слово из запроса есть в названии: по-русски, латиницей (теос → teos) или по основе (клубника → клубн…). */
        private fun hit(word: String, title: String): Boolean {
            val t = title.lowercase().replace('ё', 'е')
            val stem = if (word.length > 5) word.take(word.length - 2) else word
            return stem in t || translit(word) in t || translit(stem) in t
        }

        fun score(q: String, title: String): Int = words(q).count { hit(it, title) }

        /** Все значимые слова запроса есть в названии товара — иначе это не тот продукт. */
        fun matches(q: String, title: String): Boolean {
            val w = words(q)
            return w.isNotEmpty() && title.isNotBlank() && w.all { hit(it, title) }
        }

        private fun decode(s: String) = s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&amp;", "&")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }

        fun textOf(html: String): String = decode(
            html.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<[^>]+>"), " ")
        ).replace(Regex("\\s+"), " ")

        fun httpGet(url: String): String? = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 10000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9")
            try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
        }.getOrNull()
    }
}
