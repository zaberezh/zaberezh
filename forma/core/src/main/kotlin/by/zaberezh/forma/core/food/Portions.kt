package by.zaberezh.forma.core.food

import java.net.URLEncoder

/**
 * Добавки к блюду едят порцией, а не упаковкой: соус, сметана, варенье, сыр, масло…
 * Порция (г) по основе слова; null — это не добавка.
 */
private val CONDIMENTS = listOf(
    "кетчуп" to 20.0, "майонез" to 20.0, "соус" to 20.0, "горчиц" to 10.0, "аджик" to 15.0, "хрен" to 10.0,
    "сметан" to 30.0, "сливк" to 20.0, "сгущ" to 20.0, "варень" to 20.0, "джем" to 20.0, "конфитюр" to 20.0, "повидл" to 20.0,
    "мед" to 15.0, "мёд" to 15.0, "сироп" to 15.0, "топпинг" to 15.0, "паст" to 15.0, "нутелл" to 15.0,
    "сыр" to 20.0, "масл" to 10.0, "сахар" to 5.0, "кунжут" to 5.0,
)

fun condimentPortion(name: String): Double? {
    val n = name.lowercase().replace('ё', 'е')
    if (Regex("сырок|сырник|сырники|творожн").containsMatchIn(n)) return null // это отдельные блюда, не добавка
    // добавка — это само блюдо («соус барбекю», «натуральный мед»), а не вкус («чипсы сметана и лук»)
    val head = Regex("\\p{L}+").findAll(n).take(2).map { it.value }.toList()
    return CONDIMENTS.firstOrNull { (stem, _) -> head.any { it.startsWith(stem.replace('ё', 'е')) } }?.second
}

/**
 * Поиск калорийности в интернете прямо с телефона — вместо веб-поиска посредника API.
 * Возвращает строки вокруг «ккал» из выдачи поисковика: их читает Claude как подсказку.
 */
object WebHints {
    fun find(query: String, fetch: (String) -> String? = Edostavka::httpGet): List<String> {
        val q = URLEncoder.encode("$query калорийность кбжу на 100 г", "UTF-8")
        for (src in listOf("https://html.duckduckgo.com/html/?q=$q", "https://www.bing.com/search?q=$q")) {
            val text = fetch(src)?.let(Edostavka::textOf) ?: continue
            val out = Regex("ккал|калори", RegexOption.IGNORE_CASE).findAll(text).map { m ->
                text.substring((m.range.first - 160).coerceAtLeast(0), (m.range.last + 120).coerceAtMost(text.length)).trim()
            }.distinct().take(6).toList()
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }
}
