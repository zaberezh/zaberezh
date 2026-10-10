package by.zaberezh.forma.core.food

import by.zaberezh.forma.core.store.Store

/** Цепочка поиска КБЖУ: своя библиотека → ИИ с веб-поиском → ручной ввод. */
fun interface FoodResolver { fun resolve(text: String): List<FoodItem>? }

internal val GRAMS = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:г|гр|g|грамм\\p{L}*)(?=\\s|$)")
/** Разделители позиций: , ; + перенос строки; запятая внутри числа («кола 0,5») — не разделитель. */
internal val PARTS = Regex("[;+\\n]|,(?!\\d)|(?<!\\d),")
internal val COUNT = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(?:шт\\.?|x|х)?\\s+")

/**
 * Разбирает «гречка 200г, 2 яйца + шаурма шеф» по своей библиотеке.
 * Возвращает null, если хоть одна часть не найдена (тогда идём к ИИ).
 */
class LibraryResolver(private val store: Store) : FoodResolver {
    override fun resolve(text: String): List<FoodItem>? {
        val lib = FoodModule.library(store).associateBy { norm(it.name) } +
            FoodModule.library(store).flatMap { f -> f.aliases.map { norm(it) to f } }
        val parts = splitParts(text)
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

fun splitParts(text: String) = text.split(PARTS).flatMap(::splitAnd).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * «банан и яблоко» — две позиции, а «пирог с мясом и сыром», «блины с творогом и изюмом» — одна:
 * после «с …» слово в творительном падеже (сыром, изюмом, грибами) — это ещё начинка, а не новое блюдо.
 */
fun splitAnd(chunk: String): List<String> {
    val out = mutableListOf<String>()
    for (piece in chunk.split(Regex("\\s+и\\s+"))) {
        val prev = out.lastOrNull()
        val first = Regex("\\p{L}+").find(piece.lowercase())?.value.orEmpty()
        if (prev != null && Regex("(?i)(^|\\s)(с|со)\\s").containsMatchIn(prev) && INSTRUMENTAL.containsMatchIn(first)) out[out.size - 1] = "$prev и $piece"
        else out += piece
    }
    return out
}
private val INSTRUMENTAL = Regex("(ом|ем|ём|ой|ей|ою|ею|ами|ями|ью)$")

/** Блюда с начинкой: «пирог с мясом» — это пирог, а не «пирог» + «мясо». */
internal val STUFFED = Regex("^(пирог|пирожок|пирожки|пирожка|блин|блины|блинчик|блинчики|пицц|вареник|пельмен|чебурек|беляш|самс|шаурм|шаверм|лаваш|хачапури|сэндвич|бутерброд|круассан|булочк|булк|слойк|омлет|запеканк|сырник|лазань|ролл|буррито|хот|тост|ватрушк|кекс|маффин|торт|рулет|кулебяк|расстега)")

/** Общие слова еды: если позиция только из них — это не товар магазина (гречка, курица, яйца…). */
internal val GENERIC = setOf(
    "гречка", "гречки", "рис", "риса", "макароны", "паста", "курица", "куриная", "куриное", "грудка", "филе", "бедро", "яйцо", "яйца",
    "яиц", "творог", "овсянка", "овсяная", "каша", "картошка", "картофель", "пюре", "хлеб", "батон", "банан", "бананы", "яблоко", "яблоки",
    "молоко", "мясо", "говядина", "свинина", "рыба", "лосось", "тунец", "салат", "суп", "борщ", "котлета", "котлеты", "сыр", "масло",
    "вареная", "вареный", "вареное", "жареная", "жареный", "запеченная", "тушеная", "отварная", "сырой", "сухая", "огурец", "помидор",
    "овощи", "фрукты", "вода", "чай", "кофе", "сахар", "мед", "орехи", "шаурма", "пицца", "бургер",
)

data class ShopResult(val items: List<FoodItem>, val unresolved: List<String>, val hints: List<ShopPage>)

