package by.zaberezh.forma.core.food

import by.zaberezh.forma.core.store.Store

/** Цепочка поиска КБЖУ: своя библиотека → ИИ с веб-поиском → ручной ввод. */
fun interface FoodResolver { fun resolve(text: String): List<FoodItem>? }

internal val GRAMS = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:г|гр|g|грамм\\p{L}*)(?=\\s|$)")
internal val COUNT = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(?:шт\\.?|x|х)?\\s+")

/**
 * Разбирает «гречка 200г, 2 яйца + шаурма шеф» по своей библиотеке.
 * Возвращает null, если хоть одна часть не найдена (тогда идём к ИИ).
 */
class LibraryResolver(private val store: Store) : FoodResolver {
    override fun resolve(text: String): List<FoodItem>? {
        val lib = FoodModule.library(store).associateBy { norm(it.name) } +
            FoodModule.library(store).flatMap { f -> f.aliases.map { norm(it) to f } }
        val parts = text.split(Regex("[,;+\\n]| и ")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.map { part ->
            var rest = part.lowercase()
            val grams = GRAMS.find(rest)?.also { rest = rest.removeRange(it.range) }?.groupValues?.get(1)?.replace(',', '.')?.toDouble()
            val count = COUNT.find(rest)?.also { rest = rest.removeRange(it.range) }?.groupValues?.get(1)?.replace(',', '.')?.toDouble()
            val f = lib[norm(rest)] ?: return null
            FoodItem(f.name, grams ?: (f.grams * (count ?: 1.0)), f.per100, f.source.ifEmpty { "библиотека" }, "high")
        }
    }
}

fun splitParts(text: String) = text.split(Regex("[,;+\\n]| и ")).map { it.trim() }.filter { it.isNotEmpty() }

/** Общие слова еды: если позиция только из них — это не товар магазина (гречка, курица, яйца…). */
private val GENERIC = setOf(
    "гречка", "гречки", "рис", "риса", "макароны", "паста", "курица", "куриная", "куриное", "грудка", "филе", "бедро", "яйцо", "яйца",
    "яиц", "творог", "овсянка", "овсяная", "каша", "картошка", "картофель", "пюре", "хлеб", "батон", "банан", "бананы", "яблоко", "яблоки",
    "молоко", "мясо", "говядина", "свинина", "рыба", "лосось", "тунец", "салат", "суп", "борщ", "котлета", "котлеты", "сыр", "масло",
    "вареная", "вареный", "вареное", "жареная", "жареный", "запеченная", "тушеная", "отварная", "сырой", "сухая", "огурец", "помидор",
    "овощи", "фрукты", "вода", "чай", "кофе", "сахар", "мед", "орехи", "шаурма", "пицца", "бургер",
)

data class ShopResult(val items: List<FoodItem>, val unresolved: List<String>, val hints: List<ShopPage>)

/** Магазинные позиции — напрямую с edostavka.by; остальное (и не найденное) вернётся в unresolved. */
fun shopResolve(text: String, edo: Edostavka): ShopResult {
    val items = mutableListOf<FoodItem>(); val left = mutableListOf<String>(); val hints = mutableListOf<ShopPage>()
    for (part in splitParts(text)) {
        val words = Edostavka.cleanQuery(part).split(" ").filter { it.length >= 3 }
        if (words.isEmpty() || words.all { it in GENERIC }) { left += part; continue }
        val cands = runCatching { edo.candidates(part) }.getOrDefault(emptyList())
        val hit = cands.firstOrNull { Edostavka.matches(part, it.title) && it.per100 != null }
        if (hit == null) { left += part; hints += cands.take(2); continue }
        val low = part.lowercase()
        val grams = GRAMS.find(low)?.groupValues?.get(1)?.replace(',', '.')?.toDouble()
        val count = COUNT.find(low)?.groupValues?.get(1)?.replace(',', '.')?.toDouble()
        items += FoodItem(hit.title, grams ?: ((hit.packGrams ?: 100.0) * (count ?: 1.0)), hit.per100!!, hit.url, "high")
    }
    return ShopResult(items, left, hints)
}
