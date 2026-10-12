package by.zaberezh.forma.core.food

import java.net.URLEncoder

/**
 * FatSecret (fatsecret.ru): большая база блюд и продуктов — домашняя еда («пирог с мясом», «борщ»), бренды, сети.
 * КБЖУ есть прямо в выдаче поиска: «в 100г - Калории: 376ккал | Жир: 22,90г | Углев: 29,10г | Белк: 13,00г»
 * или на порцию («в 1 пирожное (28г)», «в 1 кусок» + «100 г - 285ккал») — тогда пересчитываем на 100 г,
 * а масса порции идёт в вес по умолчанию.
 *
 * Сайт работает только по http: у fatsecret.ru сертификат просрочен с 2022 года и выписан на fatsecret.com,
 * https отклоняет любой нормальный клиент. Ходим за одними цифрами КБЖУ, ничего личного не отправляем.
 */
class FatSecret(private val fetch: (String) -> String?, private val log: (String) -> Unit = {}) : Shop {
    override val title = "FatSecret"

    override fun search(query: String): List<ShopItem> {
        val html = fetch("$SITE$PATH/search?q=" + URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
            ?: return emptyList<ShopItem>().also { log("FatSecret: нет ответа") }
        return parse(html).also { log("FatSecret: «$query» — ${it.size}") }
    }

    companion object {
        private const val SITE = "http://mobile.fatsecret.ru"
        private const val PATH = "/%D0%BA%D0%B0%D0%BB%D0%BE%D1%80%D0%B8%D0%B8-%D0%BF%D0%B8%D1%82%D0%B0%D0%BD%D0%B8%D0%B5"   // /калории-питание
        private const val N = "(\\d+(?:[.,]\\d+)?)"
        private val ROW = Regex("<td colspan=\"3\" onclick=[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
        private val LINK = Regex("<a class=\"inner-link\" href=\"([^\"]+)\">([^<]+)</a>")
        private val BRAND = Regex("<a class=\"brand\"[^>]*>\\(([^<]*)\\)</a>")
        private val LINE = Regex("в\\s+([^<]*?)\\s+-\\s+Калории:\\s*$N\\s*ккал\\s*\\|\\s*Жир:\\s*$N\\s*г\\s*\\|\\s*Углев:\\s*$N\\s*г\\s*\\|\\s*Белк:\\s*$N\\s*г")
        private val HUNDRED = Regex("100\\s*г\\s*-\\s*$N\\s*ккал")
        private fun d(s: String) = s.replace(',', '.').toDouble()

        /** Строки выдачи: название (с брендом в скобках), КБЖУ на 100 г, масса порции, если порция — не 100 г. */
        fun parse(html: String): List<ShopItem> = ROW.findAll(html).mapNotNull { r ->
            val row = r.groupValues[1]
            val link = LINK.find(row) ?: return@mapNotNull null
            val name = unescape(link.groupValues[2]).trim()
            val brand = BRAND.find(row)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
            val l = LINE.find(row) ?: return@mapNotNull null
            val serving = l.groupValues[1].trim()
            val (kcal, f, c, p) = (2..5).map { d(l.groupValues[it]) }
            val grams = servingGrams(serving, kcal, HUNDRED.find(row)?.groupValues?.get(1)?.let(::d)) ?: return@mapNotNull null
            val k = 100.0 / grams
            val full = if (brand != null) "$name ($brand)" else name
            fun r(x: Double) = kotlin.math.round(x * k * 10) / 10
            val m = WebFood.labelMacro(r(kcal), r(p), r(f), r(c), full) ?: return@mapNotNull null
            ShopItem("FatSecret", link.groupValues[1], full, SITE + link.groupValues[1], m,
                pieceGrams = grams.takeIf { !Regex("^100\\s*(?:г|мл)$").matches(serving) }, serving = serving.takeIf { grams != 100.0 })
        }.toList()

        /** Масса порции: «100г» → 100, «1 пирожное (28г)» → 28, «1 кусок» при «100 г - 285ккал» → 313/285·100. */
        fun servingGrams(serving: String, kcal: Double, kcal100: Double?): Double? {
            Regex("^$N\\s*(?:г|мл)$").find(serving)?.let { return d(it.groupValues[1]).takeIf { g -> g > 0 } }
            Regex("\\($N\\s*(?:г|мл)\\)").find(serving)?.let { return d(it.groupValues[1]).takeIf { g -> g > 0 } }
            if (kcal100 != null && kcal100 > 0 && kcal > 0) return (kcal / kcal100 * 100).takeIf { it in 1.0..2000.0 }
            return null
        }

        private fun unescape(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
    }
}
