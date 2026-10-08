package by.zaberezh.forma.core.food

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** Страница магазина, найденная для позиции: подсказка для Claude (если сам товар не подошёл уверенно). */
data class ShopPage(val url: String, val title: String, val snippet: String, val per100: Macro?, val packGrams: Double?)

/** Выполняет скрипт на странице edostavka.by в настоящем браузере и возвращает результат JSON-строкой (null — не вышло). */
fun interface PageRunner { fun run(script: String): String? }

/**
 * edostavka.by: точные КБЖУ магазинных товаров. Сайт пускает только настоящий браузер (перед ним проверка, которую
 * простой запрос не проходит), поэтому поиск идёт со страницы самого сайта в браузере телефона ([PageRunner]) —
 * теми же запросами, что делает сайт, когда ищешь в нём руками. КБЖУ — из карточки товара (в выдаче их обычно нет).
 */
class Edostavka(private val page: PageRunner?, private val log: (String) -> Unit = {}) : Shop {
    override val title = "edostavka.by"

    override fun search(query: String): List<ShopItem> {
        val run = page ?: return emptyList()
        val raw = run.run("($SCRIPT).search(${JsonPrimitive(query)})") ?: return emptyList<ShopItem>().also { log("edostavka: страница не ответила") }
        return parseSearch(raw, log).also { log("edostavka «$query»: товаров ${it.size}") }
    }

    override fun details(item: ShopItem): ShopItem? {
        if (item.per100 != null) return item
        val raw = page?.run("($SCRIPT).product(${JsonPrimitive(item.id)})") ?: return null
        val o = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        return parseItem(o)?.let { item.copy(per100 = it.per100, packGrams = it.packGrams ?: item.packGrams) }?.takeIf { it.per100 != null }
    }

    companion object {
        private const val NUM = "(\\d+(?:[.,]\\d+)?)"

        /** Скрипт для страницы сайта: поиск (search.json сайта) и карточка товара; лежит в resources/shops/edostavka.js. */
        val SCRIPT: String by lazy { Edostavka::class.java.getResource("/shops/edostavka.js")!!.readText().trim() }

        /** Ответ скрипта поиска: {items:[{id, name, pack, props:{Белки, Жиры, Углеводы, Энергетическая ценность}}]} или {error}. */
        fun parseSearch(json: String, log: (String) -> Unit = {}): List<ShopItem> {
            val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return emptyList()
            root.str("error")?.let { log("edostavka: $it"); return emptyList() }
            return (root["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseItem) }
        }

        private fun parseItem(o: JsonObject): ShopItem? {
            val id = o.str("id") ?: return null
            val raw = o.str("name").orEmpty().trim().trimEnd(',').trim()
            val pack = o.str("pack").orEmpty().trim()
            // в названии не всегда есть масса («Сыр Гауда Премиум 45%,») — тогда её даёт packagingInfo («200 г»)
            val name = if (pack.isNotEmpty() && packGrams(raw) == null && packGrams(pack) != null) "$raw, $pack" else raw
            val props = (o["props"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
            return ShopItem("edostavka.by", id, name, "https://edostavka.by/product/$id", macroOf(props, name), packGrams(name), pieceGrams(name))
        }

        /** КБЖУ из свойств товара: «Белки 3», «Жиры 3.3», «Углеводы 4», «Энергетическая ценность 56,8 ккал/237,4 кДж». */
        fun macroOf(props: Map<String, String>, name: String = ""): Macro? {
            fun value(vararg keys: String) = props.entries.firstOrNull { (k, _) -> keys.any { it in k.lowercase() } }?.value
            fun num(s: String?) = s?.let { Regex(NUM).find(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }
            val energy = value("энерг", "калор")
            val kcal = energy?.let { e ->
                Regex("$NUM\\s*ккал", RegexOption.IGNORE_CASE).find(e)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                    ?: Regex("$NUM\\s*кдж", RegexOption.IGNORE_CASE).find(e)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.div(4.184)
            }
            val m = WebFood.labelMacro(kcal, num(value("белк")), num(value("жир")), num(value("углев")), name) ?: return null
            return m.copy(fib = num(value("клетч", "пищевые волокна")) ?: 0.0)
        }

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

        /** Запрос без количеств: «2 теос про клубника 330г» → «теос про клубника». */
        fun cleanQuery(q: String): String = q.lowercase().replace('ё', 'е')
            .replace(Regex("\\d+(?:[.,]\\d+)?\\s*(?:г|гр|грамм\\w*|мл|шт\\.?|x|х|кг|л)?(?=\\s|$)"), " ")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

        private fun decode(s: String) = s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&amp;", "&")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }

        fun textOf(html: String): String = decode(
            html.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<[^>]+>"), " ")
        ).replace(Regex("\\s+"), " ")

        fun httpGet(url: String): String? = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9")
            try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
        }.getOrNull()
    }
}
