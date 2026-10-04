package by.zaberezh.forma.core.food

import by.zaberezh.forma.core.store.Store

/** Итог поиска: позиции, откуда они («2 таблица · 1 интернет»), что не нашлось. */
data class FoodResult(val items: List<FoodItem>, val how: String, val missing: List<String>, val error: Throwable? = null)

/**
 * Поиск КБЖУ по шагам — от мгновенного и точного к медленному:
 * 1) меню заведений (KFC, Кинза) → 2) своя библиотека → 3) таблица частых продуктов (банан, гречка, яйца…) — всё офлайн;
 * 4) edostavka.by (магазинные товары, точные цифры) → 5) интернет без ИИ (поисковик + сайты калорийности);
 * 6) Claude — только то, что не нашлось нигде.
 * Сетевые шаги делят общий бюджет времени, поэтому поиск не «висит».
 */
/** «с» / «со» между блюдами: «гречка с курицей». */
private val WITH = Regex("\\s+(?:с|со)\\s+", RegexOption.IGNORE_CASE)

/**
 * Если в запросе вес указан явно («шоколадка 30г»), он главнее оценки со стороны: значения на 100 г те же,
 * масса — как написал человек. Сопоставление по порядку, когда позиций столько же, сколько частей запроса.
 */
fun keepGrams(parts: List<String>, items: List<FoodItem>): List<FoodItem> {
    if (parts.size != items.size) return items
    return items.mapIndexed { i, it -> WebFood.gramsOf(parts[i])?.takeIf { g -> g > 0 }?.let { g -> it.copy(grams = g) } ?: it }
}

class FoodPipeline(
    private val store: Store,
    fetch: (String) -> String? = Edostavka::httpGet,
    private val claude: ((rest: String, hints: List<ShopPage>) -> List<FoodItem>)? = null,
    private val stage: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
    netBudgetMs: Long = 25_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val deadline = clock() + netBudgetMs
    private val timed: (String) -> String? = { url -> if (clock() > deadline) null.also { log("время вышло: $url") } else fetch(url) }
    private val shop = Edostavka(timed, log)
    private val web = WebFood(timed, log)

    fun run(text: String): FoodResult {
        val items = mutableListOf<FoodItem>()
        val how = linkedMapOf<String, Int>()
        fun add(step: String, found: List<FoodItem>) { if (found.isNotEmpty()) { items += found; how[step] = (how[step] ?: 0) + found.size } }

        val menu = Menus.resolve(text)
        add("меню заведения", menu.items)
        var rest = if (menu.items.isEmpty()) splitParts(text) else menu.rest.flatMap(::splitParts)

        val lib = LibraryResolver(store)
        rest = rest.filter { part -> lib.resolve(part)?.also { add("библиотека", it) } == null }

        if (rest.isNotEmpty()) {
            val basic = Menus.basic.resolve(rest.joinToString(", "))
            add("таблица", basic.items)
            rest = basic.rest
            // «макароны с 2 котлетами», «пюре с котлетой»: не нашлось целиком — пробуем по частям
            rest = rest.filter { part ->
                val pieces = part.split(WITH).map { it.trim() }.filter { it.isNotEmpty() }
                if (pieces.size < 2) return@filter true
                val r = Menus.basic.resolve(pieces.joinToString(", "))
                if (r.rest.isEmpty() && r.items.isNotEmpty()) { add("таблица", r.items); false } else true
            }
        }

        var hints = emptyList<ShopPage>()
        if (rest.isNotEmpty()) {
            stage("ищу на edostavka.by…")
            // сбой магазина (сеть, разметка сайта) не должен терять то, что уже нашлось в таблице
            runCatching { shopResolve(rest.joinToString(", "), shop) }
                .onSuccess { r -> add("edostavka.by", r.items); rest = r.unresolved; hints = r.hints }
                .onFailure { log("edostavka: ${it.message}") }
        }

        if (rest.isNotEmpty()) {
            stage("ищу в интернете…")
            rest = rest.filter { part -> runCatching { web.find(part) }.getOrNull()?.also { add("интернет", listOf(it)) } == null }
        }

        var error: Throwable? = null
        val ask = claude
        if (rest.isNotEmpty() && ask != null) {
            stage("спрашиваю Claude…")
            // ошибка Claude не теряет то, что уже найдено
            val asked = rest
            runCatching { ask(asked.joinToString(", "), hints) }
                .onSuccess { add("Claude", keepGrams(asked, it)); rest = emptyList() }
                .onFailure { error = it; log("Claude: ${it.message}") }
        }
        return FoodResult(items, how.entries.joinToString(" · ") { "${it.value} ${it.key}" }, rest, error)
    }
}
