package by.zaberezh.forma.core.food

import java.net.URLEncoder
import kotlin.math.abs

/**
 * FatSecret (fatsecret.ru): большая база блюд и продуктов — домашняя еда («пирог с мясом», «борщ»), бренды, сети.
 * КБЖУ есть прямо в выдаче поиска: «в 100г - Калории: 376ккал | …» или на порцию («в 1 пирожок», «в 1 пирожное (28г)»)
 * плюс «Другое количество: 1 порция - 363ккал, 100 г - 242ккал» — по ним пересчитываем на 100 г и узнаём вес штуки.
 *
 * Какая запись берётся:
 * - ищем блюдо так, как его написал человек, со связками: «пирог с грибами» и «пирог грибами» — разные выдачи;
 * - лишний ингредиент — уже другое блюдо: на «пирог с грибами» не «пирог с курицей и грибами», если есть ближе;
 * - «общая» запись (без марки) — это и есть обычное блюдо, она главнее марок;
 * - общей нет — у марок цифры сильно разные (блины с творогом и изюмом: 168…274 ккал), берём типичную — ближайшую к медиане.
 * Вес — из запроса и порций FatSecret («1 пирожок» = 75 г), иначе обычная порция блюда ([Dish]).
 *
 * Сайт работает только по http: у fatsecret.ru сертификат просрочен с 2022 года и выписан на fatsecret.com,
 * https отклоняет любой нормальный клиент. Ходим за одними цифрами КБЖУ, ничего личного не отправляем.
 */
class FatSecret(private val fetch: (String) -> String?, private val log: (String) -> Unit = {}) {

    /** Порция у FatSecret, на одну штуку: «1 пирожок» — 75 г, «3 сырника (200г)» — сырник по 67 г. */
    data class Serving(val unit: String, val grams: Double)

    data class Entry(val name: String, val brand: String?, val url: String, val per100: Macro, val servings: List<Serving>, val rank: Int) {
        /** Без марки — «общая» запись FatSecret: обычное домашнее блюдо. */
        val generic get() = brand == null
        val title get() = brand?.let { "$name ($it)" } ?: name

        /**
         * Вес одной штуки того, что съедено: кусок, штука или та же вещь («1 котлета» для «котлеты»).
         * loose — блюдо незнакомое (не суп, не пирог…), тогда годится любая штучная порция («1 пирожное (28г)»).
         */
        fun piece(word: String?, loose: Boolean): Double? =
            servings.firstOrNull { it.unit in SLICE }?.grams
                ?: servings.firstOrNull { word != null && it.unit !in PORTION && it.unit !in PACK && Dish.sameWord(it.unit, word) }?.grams
                ?: servings.firstOrNull { it.unit in PIECE }?.grams
                ?: if (loose) servings.firstOrNull { it.unit !in PORTION && it.unit !in PACK }?.grams else null

        fun portion(): Double? = (servings.firstOrNull { it.unit == "порция" } ?: servings.firstOrNull { it.unit in PORTION })?.grams
    }

    /** Выбранная запись; typicalOf > 0 — типичная из стольких марок. */
    data class Pick(val entry: Entry, val typicalOf: Int = 0)

    fun search(query: String): List<Entry> {
        val html = fetch("$SITE$PATH/search?q=" + URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
            ?: return emptyList<Entry>().also { log("FatSecret: нет ответа") }
        return parse(html).also { log("FatSecret: «$query» — ${it.size}") }
    }

    /** Одна позиция; null — похожего нет. */
    fun find(part: String): FoodItem? = lookup(part).first

    /** Позиции; что не нашлось — дальше (интернет, Claude), с похожими записями в подсказку для Claude. */
    fun resolve(parts: List<String>): ShopResult {
        val items = mutableListOf<FoodItem>(); val left = mutableListOf<String>(); val hints = mutableListOf<ShopPage>()
        for (part in parts) {
            val (item, seen) = runCatching { lookup(part) }.getOrElse { log("FatSecret: ${it.message}"); null to emptyList() }
            if (item != null) { items += item; continue }
            left += part
            val dish = Dish.order(part).dish
            hints += seen.filter { ShopMatch.fit(dish, it.title).score >= 0.4 }.take(2).map { e ->
                ShopPage(e.url, e.title, "на 100 г: ${e.per100.kcal} ккал, Б ${e.per100.p}, Ж ${e.per100.f}, У ${e.per100.c}", e.per100, null)
            }
        }
        return ShopResult(items, left, hints)
    }

    /** Найденная позиция и вся выдача последнего запроса (для подсказки, если ничего не подошло). */
    private fun lookup(part: String): Pair<FoodItem?, List<Entry>> {
        val o = Dish.order(part)
        if (o.dish.isBlank()) return null to emptyList()
        var seen = emptyList<Entry>()
        for (q in variants(o.dish)) {
            val found = search(q)
            if (found.isEmpty()) continue
            seen = found
            val p = pick(o.dish, found) ?: continue
            return item(part, o, p, found) to found
        }
        log("FatSecret: «$part» — похожего нет")
        return null to seen
    }

    private fun item(part: String, o: Dish.Order, p: Pick, all: List<Entry>): FoodItem {
        val e = p.entry
        val typical = Dish.typical(o.dish)
        val word = typical?.first ?: o.dish.split(" ").firstOrNull()
        // вес штуки и порции — у самой записи, у той же записи с другой порцией или у «общей» записи того же блюда:
        // у марок часто только «100 г», а «Блины с творогом» без марки знают, что блин — 60 г
        val donors = listOf(e) + all.filter { it !== e && (it.title.equals(e.title, true) || it.generic && Dish.sameWord(head(it.name), head(e.name))) }
        val piece = donors.firstNotNullOfOrNull { it.piece(word, loose = typical == null) }
        val portion = donors.firstNotNullOfOrNull { it.portion() }
        val (grams, note) = Dish.portion(part, piece, portion)
        log("FatSecret: «$part» → «${e.title}»" + (if (p.typicalOf > 0) " (типичное из ${p.typicalOf})" else "") + ", ${Math.round(grams)} г")
        val name = if (p.typicalOf > 0) tidy(e.name) else tidy(e.name) + (e.brand?.let { " ($it)" } ?: "")
        val src = "FatSecret" + (if (p.typicalOf > 0) " · типичное из ${p.typicalOf} вариантов" else "") + (note?.let { " · $it" } ?: "")
        return FoodItem(name, Math.round(grams * 10) / 10.0, e.per100, src, "medium")
    }

    companion object {
        private const val SITE = "http://mobile.fatsecret.ru"
        private const val PATH = "/%D0%BA%D0%B0%D0%BB%D0%BE%D1%80%D0%B8%D0%B8-%D0%BF%D0%B8%D1%82%D0%B0%D0%BD%D0%B8%D0%B5"   // /калории-питание
        private const val N = "(\\d+(?:[.,]\\d+)?)"
        private val ROW = Regex("<td colspan=\"3\" onclick=[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
        private val LINK = Regex("<a class=\"inner-link\" href=\"([^\"]+)\">([^<]+)</a>")
        private val BRAND = Regex("<a class=\"brand\"[^>]*>\\(([^<]*)\\)</a>")
        private val LINE = Regex("в\\s+([^<]*?)\\s+-\\s+Калории:\\s*$N\\s*ккал\\s*\\|\\s*Жир:\\s*$N\\s*г\\s*\\|\\s*Углев:\\s*$N\\s*г\\s*\\|\\s*Белк:\\s*$N\\s*г")
        private val OTHER = Regex("Другое количество:(.*?)(?:<i>|</div>)", RegexOption.DOT_MATCHES_ALL)
        private val QTY = Regex("([^,]+?)\\s+-\\s+$N\\s*ккал")
        private val WEIGHT = Regex("^$N\\s*(?:г|гр|g|мл|ml)$", RegexOption.IGNORE_CASE)
        private val PARENS = Regex("\\($N\\s*(?:г|гр|g|мл|ml)\\)", RegexOption.IGNORE_CASE)
        private val COUNTED = Regex("^$N\\s+(\\p{L}+)")
        /** Порции — не штуки. */
        private val PORTION = setOf("порция", "чашка", "стакан", "тарелка", "миска")
        /** Упаковки и ложки — не то, что едят «штукой». */
        private val PACK = setOf("упаковка", "пачка", "банка", "бутылка", "контейнер", "ст", "ч", "столовая", "чайная", "ложка", "унция", "фунт", "пицца", "торт", "пирог")
        private val SLICE = setOf("кусок", "кусочек", "ломтик")
        private val PIECE = setOf("штука", "шт", "средний", "средняя", "среднее")
        private fun d(s: String) = s.replace(',', '.').toDouble()

        /** Строки выдачи по порядку FatSecret. */
        fun parse(html: String): List<Entry> = ROW.findAll(html).toList().mapIndexedNotNull { i, r -> entry(r.groupValues[1], i) }

        private fun entry(row: String, rank: Int): Entry? {
            val link = LINK.find(row) ?: return null
            val name = unescape(link.groupValues[2]).replace(Regex("\\s+"), " ").trim()
            val brand = BRAND.find(row)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
            val l = LINE.find(row) ?: return null
            val label = l.groupValues[1].trim()
            val (kcal, f, c, p) = (2..5).map { d(l.groupValues[it]) }
            val others = OTHER.find(row)?.groupValues?.get(1)?.let { o ->
                QTY.findAll(unescape(o)).map { it.groupValues[1].trim() to d(it.groupValues[2]) }.toList()
            }.orEmpty()
            // масса основной порции: «100г», «1 пирожное (28г)», иначе по «100 г - 242ккал» из других количеств
            val kcal100 = others.firstNotNullOfOrNull { (lab, k) -> WEIGHT.find(lab)?.let { k * 100 / d(it.groupValues[1]) } }
            val main = gramsIn(label) ?: kcal100?.takeIf { it > 0 && kcal > 0 }?.let { kcal / it * 100 } ?: return null
            if (main !in 1.0..3000.0) return null
            val k = 100 / main
            fun r(x: Double) = Math.round(x * k * 10) / 10.0
            val m = WebFood.labelMacro(r(kcal), r(p), r(f), r(c), "$name ${brand.orEmpty()}") ?: return null
            val per100kcal = r(kcal)
            val servings = buildList {
                serving(label, main)?.let(::add)
                if (per100kcal > 0) others.forEach { (lab, kc) -> serving(lab, kc / per100kcal * 100)?.let(::add) }
            }
            return Entry(name, brand, SITE + link.groupValues[1], m, servings, rank)
        }

        /** Масса из подписи порции: «100г» → 100, «1 пирожное (28г)» → 28; null — массы в подписи нет. */
        private fun gramsIn(label: String): Double? = (WEIGHT.find(label) ?: PARENS.find(label))?.let { d(it.groupValues[1]) }?.takeIf { it > 0 }

        /** «3 сырника (200г)» → сырник по 66.7 г; «100 г» — не порция, а мера. */
        private fun serving(label: String, grams: Double): Serving? {
            if (WEIGHT.matches(label) || grams <= 0 || grams > 3000) return null
            val low = label.lowercase().replace('ё', 'е')
            val m = COUNTED.find(low)
            val n = m?.groupValues?.get(1)?.let(::d)?.takeIf { it > 0 } ?: 1.0
            val unit = m?.groupValues?.get(2) ?: Regex("\\p{L}+").find(low)?.value ?: return null
            return Serving(unit, grams / n)
        }

        /**
         * Лучшая запись для блюда. Сначала — сколько слов блюда нашлось (с маркой), потом — меньше лишних
         * ингредиентов (без марки), потом — «общая» запись; среди равных марок без общей — типичная по калориям.
         */
        fun pick(dish: String, entries: List<Entry>): Pick? {
            data class C(val e: Entry, val score: Double, val extra: Int)
            val cs = entries.mapNotNull { e ->
                val fit = ShopMatch.fit(dish, e.name + " " + e.brand.orEmpty())
                if (fit.ok) C(e, fit.score, ShopMatch.fit(dish, e.name).extra) else null
            }
            val best = cs.minWithOrNull(compareBy<C>({ -it.score }, { it.extra }, { if (it.e.generic) 0 else 1 }, { it.e.rank })) ?: return null
            val tier = cs.filter { abs(it.score - best.score) < 0.01 && it.extra == best.extra }
            tier.firstOrNull { it.e.generic }?.let { return Pick(it.e) }
            if (tier.size < 3) return Pick(best.e)
            val kcal = tier.map { it.e.per100.kcal }.sorted()
            val median = kcal[kcal.size / 2]
            return Pick(tier.minWith(compareBy<C>({ abs(it.e.per100.kcal - median) }, { it.e.rank })).e, tier.size)
        }

        /** Что спросить у FatSecret: как написано; «пирожков» → «пирожки» (их поиск не понимает родительный); без связок — напоследок. */
        fun variants(dish: String): List<String> {
            val w = dish.split(" ").filter { it.isNotBlank() }
            if (w.isEmpty()) return emptyList()
            val out = linkedSetOf(dish)
            Dish.lemma(w.first())?.let { out += (listOf(it) + w.drop(1)).joinToString(" ") }
            out += ShopMatch.queryWords(dish).joinToString(" ")
            return out.filter { it.isNotBlank() }
        }

        private fun head(name: String) = Regex("\\p{L}+").find(name.lowercase())?.value.orEmpty()

        /** «Пирожок с Грибами» → «Пирожок с грибами»: у FatSecret каждое слово с большой буквы. Латиницу (марки) не трогаем. */
        fun tidy(name: String): String = name.split(" ").mapIndexed { i, w ->
            val letters = w.filter { it.isLetter() }
            val caps = letters.length >= 2 && letters.all { it.isUpperCase() }   // аббревиатура — как есть
            if (i > 0 && letters.any { it in 'А'..'я' || it == 'Ё' || it == 'ё' } && !caps) w.lowercase() else w
        }.joinToString(" ")

        private fun unescape(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
    }
}
