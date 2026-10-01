package by.zaberezh.forma.core.food

import by.zaberezh.forma.core.store.Store

/** Итог поиска: позиции, откуда они («2 таблица · 1 интернет»), что не нашлось. */
data class FoodResult(val items: List<FoodItem>, val how: String, val missing: List<String>, val error: Throwable? = null)

/**
 * Поиск КБЖУ по шагам — от мгновенного и точного к медленному:
 * 1) меню сетей (KFC) → 2) своя библиотека → 3) таблица частых продуктов (банан, гречка, яйца…) — всё офлайн;
 * 4) edostavka.by (магазинные товары, точные цифры) → 5) интернет без ИИ (поисковик + сайты калорийности);
 * 6) Claude — только то, что не нашлось нигде.
 * Сетевые шаги делят общий бюджет времени, поэтому поиск не «висит».
 */
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
        add("KFC", menu.items)
        var rest = if (menu.items.isEmpty()) splitParts(text) else menu.rest.flatMap(::splitParts)

        val lib = LibraryResolver(store)
        rest = rest.filter { part -> lib.resolve(part)?.also { add("библиотека", it) } == null }

        if (rest.isNotEmpty()) {
            val basic = Menus.basic.resolve(rest.joinToString(", "))
            add("таблица", basic.items)
            rest = basic.rest
        }

        var hints = emptyList<ShopPage>()
        if (rest.isNotEmpty()) {
            stage("ищу на edostavka.by…")
            val r = shopResolve(rest.joinToString(", "), shop)
            add("edostavka.by", r.items)
            rest = r.unresolved; hints = r.hints
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
            runCatching { ask(rest.joinToString(", "), hints) }
                .onSuccess { add("Claude", it); rest = emptyList() }
                .onFailure { error = it; log("Claude: ${it.message}") }
        }
        return FoodResult(items, how.entries.joinToString(" · ") { "${it.value} ${it.key}" }, rest, error)
    }
}
