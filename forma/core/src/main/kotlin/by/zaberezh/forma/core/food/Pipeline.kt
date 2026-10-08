package by.zaberezh.forma.core.food

import by.zaberezh.forma.core.store.Store

/** Итог поиска: позиции, откуда они («2 таблица · 1 интернет»), что не нашлось. */
data class FoodResult(val items: List<FoodItem>, val how: String, val missing: List<String>, val error: Throwable? = null)

/**
 * Поиск КБЖУ по шагам — от мгновенного и точного к медленному:
 * 1) меню заведений (KFC, Кинза) → 2) своя библиотека → 3) таблица частых продуктов (банан, гречка, яйца…) — всё офлайн;
 * 4) магазины — edostavka.by и «Соседи» сразу (товары с этикетки, точные цифры) → 5) интернет без ИИ (поисковик + сайты калорийности);
 * 6) Claude — только то, что не нашлось нигде.
 * Сетевые шаги делят общий бюджет времени, поэтому поиск не «висит».
 */
/** Что за еда — выбирает человек: домашнюю не ищем в магазинах, покупную ищем там в первую очередь. */
enum class FoodKind { AUTO, HOME, SHOP }

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
    /** Браузер со страницей edostavka.by (на телефоне — невидимый WebView); null — edostavka пропускается. */
    edostavka: PageRunner? = null,
    private val kind: FoodKind = FoodKind.AUTO,
) {
    private val deadline = clock() + netBudgetMs
    private val timed: (String) -> String? = { url -> if (clock() > deadline) null.also { log("время вышло: $url") } else fetch(url) }
    private val page = edostavka?.let { p -> PageRunner { js -> if (clock() > deadline) null.also { log("время вышло: edostavka") } else p.run(js) } }
    private val shops = ShopFinder(listOf(Edostavka(page, log), Sosedi(timed, log)), log)
    private val web = WebFood(timed, log)

    fun run(text: String): FoodResult {
        items.clear(); how.clear()

        val menu = Menus.resolve(text)
        add("меню заведения", menu.items)
        var rest = if (menu.items.isEmpty()) splitParts(text) else menu.rest.flatMap(::splitParts)

        val lib = LibraryResolver(store)
        rest = rest.filter { part -> lib.resolve(part)?.also { add("библиотека", it) } == null }

        var hints = emptyList<ShopPage>()
        // «из магазина»: сначала товары магазинов — даже «кефир» или «творог 5%» (это конкретная пачка, а не среднее по таблице)
        if (rest.isNotEmpty() && kind == FoodKind.SHOP) rest = shopStage(rest, all = true) { hints = it }

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

        // «домашняя» — в магазинах не ищем: там другой продукт с другими цифрами
        if (rest.isNotEmpty() && kind == FoodKind.AUTO) rest = shopStage(rest, all = false) { hints = it }

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
            val what = asked.joinToString(", ") + when (kind) { FoodKind.HOME -> " (домашняя еда)"; FoodKind.SHOP -> " (магазинный продукт)"; else -> "" }
            runCatching { ask(what, hints) }
                .onSuccess { add("Claude", keepGrams(asked, it)); rest = emptyList() }
                .onFailure { error = it; log("Claude: ${it.message}") }
        }
        return FoodResult(items, how.entries.joinToString(" · ") { "${it.value} ${it.key}" }, rest, error)
    }

    private val items = mutableListOf<FoodItem>()
    private val how = linkedMapOf<String, Int>()
    private fun add(step: String, found: List<FoodItem>) { if (found.isNotEmpty()) { items += found; how[step] = (how[step] ?: 0) + found.size } }

    /** Магазины; сбой (сеть, разметка сайта) не теряет найденное раньше. Возвращает то, что не нашлось. */
    private fun shopStage(rest: List<String>, all: Boolean, hints: (List<ShopPage>) -> Unit): List<String> {
        stage("ищу в магазинах (edostavka, Соседи)…")
        return runCatching { shops.resolve(rest, all) }
            .onSuccess { r -> r.items.groupBy { it.source.substringBefore(" ·") }.forEach { (shop, l) -> add(shop, l) }; hints(r.hints) }
            .onFailure { log("магазины: ${it.message}") }
            .getOrNull()?.unresolved ?: rest
    }
}
