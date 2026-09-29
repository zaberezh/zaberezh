package by.zaberezh.forma.core.food

import by.zaberezh.forma.core.store.Store

/** Цепочка поиска КБЖУ: своя библиотека → ИИ с веб-поиском → ручной ввод. */
fun interface FoodResolver { fun resolve(text: String): List<FoodItem>? }

private val GRAMS = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:г|гр|g|грамм\\p{L}*)(?=\\s|$)")
private val COUNT = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(?:шт\\.?|x|х)?\\s+")

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
