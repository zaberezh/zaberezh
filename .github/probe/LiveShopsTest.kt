package by.zaberezh.forma.core

import by.zaberezh.forma.core.food.Edostavka
import by.zaberezh.forma.core.food.PageRunner
import by.zaberezh.forma.core.food.ShopFinder
import by.zaberezh.forma.core.food.Sosedi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test

/** Только для CI-проверки вживую: «Соседи» — настоящий API, edostavka — записанные из браузера ответы. */
class LiveShopsTest {
    private val queries = listOf(
        "Печенье Lotte Choco Pie с ароматом банана", "кефир детский депи", "теос про клубника", "сырок брест-литовск ванильный",
        "сыр гауда брест литовск", "йогурт беллакт банан", "чипсы lays сметана и лук", "milka молочный шоколад", "снежок савушкин",
        "творог савушкин 5%", "батончик snickers", "квас лидский", "2 choco pie", "пельмени",
    )

    @Test fun live() {
        val mode = System.getenv("LIVE") ?: return
        val dir = File(System.getenv("LIVE_DIR") ?: "/tmp/live")
        val f = ShopFinder(emptyList())
        if (mode == "variants") {
            val all = queries.flatMap { f.variants(it) }.distinct()
            File(dir, "variants.json").writeText(JsonArray(all.map { JsonPrimitive(it) }).toString())
            println("LIVE variants ${all.size}")
            return
        }
        val rec = (File(dir, "edo.json").takeIf { it.exists() }?.readText()?.let { Json.parseToJsonElement(it) as JsonObject }) ?: JsonObject(emptyMap())
        val page = PageRunner { js ->
            val arg = js.substringAfterLast(").search(", "").substringBeforeLast(")")
            if (arg.isEmpty()) null else rec[Json.parseToJsonElement(arg).jsonPrimitive.content]?.jsonPrimitive?.content
        }
        val logs = mutableListOf<String>()
        val finder = ShopFinder(listOf(Edostavka(page) { logs += it }, Sosedi(Edostavka::httpGet) { logs += it }))
        for (q in queries) {
            val t0 = System.currentTimeMillis()
            val hits = finder.candidates(q)
            val item = finder.find(q, hits)
            println("LIVE ===== «$q» (${System.currentTimeMillis() - t0} мс)")
            println("LIVE   → " + (item?.let { "${it.name} | ${it.grams} г | ${it.per100.kcal.toInt()} ккал Б${it.per100.p} Ж${it.per100.f} У${it.per100.c} | ${it.source}" } ?: "НЕ НАЙДЕНО"))
            hits.take(4).forEach { h -> println("LIVE     ${h.shop.title.take(4)} ${(h.fit.score * 100).toInt()}% ok=${h.fit.ok} extra=${h.fit.extra} rank=${h.rank} | ${h.item.name} | ${h.item.per100?.kcal}") }
        }
        logs.filter { "товаров" in it }.take(60).forEach { println("LIVE log $it") }
    }
}
