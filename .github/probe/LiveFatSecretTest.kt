package by.zaberezh.forma.core

import by.zaberezh.forma.core.food.FatSecret
import by.zaberezh.forma.core.food.FoodPipeline
import by.zaberezh.forma.core.food.FoodKind
import by.zaberezh.forma.core.food.ShopFinder
import by.zaberezh.forma.core.food.Edostavka
import by.zaberezh.forma.core.store.MemoryStore
import kotlin.test.Test

class LiveFatSecretTest {
    @Test fun live() {
        if (System.getenv("LIVE") != "fs") return
        val f = ShopFinder(listOf(FatSecret(Edostavka::httpGet)))
        for (q in listOf("пирог с мясом и сыром", "пирог с грибами", "2 пирожка с капустой", "борщ", "блины с творогом и изюмом",
                "Lotte Choco Pie", "суп куриный с лапшой", "плов с курицей", "творог 5%", "сырники со сметаной", "драники", "оливье 200г")) {
            val r = runCatching { f.find(q) }.getOrNull()
            println("LIVE [$q] → " + (r?.let { "${it.name} ${it.grams.toInt()}г · ${it.per100.kcal.toInt()} ккал/100 Б${it.per100.p} Ж${it.per100.f} У${it.per100.c} · ${it.source}" } ?: "—"))
        }
        val p = FoodPipeline(MemoryStore(), kind = FoodKind.HOME)
        val res = p.run("пирог с грибами, драники со сметаной")
        println("LIVE pipeline: ${res.how} | " + res.items.joinToString { it.name + " " + it.grams.toInt() } + " | missing " + res.missing)
    }
}
