package by.zaberezh.forma.core

import by.zaberezh.forma.core.food.FatSecret
import by.zaberezh.forma.core.food.FoodPipeline
import by.zaberezh.forma.core.food.FoodKind
import by.zaberezh.forma.core.food.Edostavka
import by.zaberezh.forma.core.store.MemoryStore
import kotlin.test.Test

class LiveFatSecretTest {
    @Test fun live() {
        if (System.getenv("LIVE") != "fs") return
        val f = FatSecret(Edostavka::httpGet)
        for (q in listOf("пирог с грибами", "пирог с мясом и сыром", "2 пирожка с капустой", "5 пирожков с капустой", "борщ", "тарелка борща",
                "блины с творогом и изюмом", "Lotte Choco Pie", "суп куриный с лапшой", "плов с курицей", "творог 5%", "сырники со сметаной",
                "драники", "оливье 200г", "котлета куриная", "голубцы", "манты", "шаурма", "кусок пиццы пепперони", "запеканка творожная",
                "макароны по-флотски", "3 блина с мясом", "солянка", "пельмени", "оливье", "кусок пирога с капустой", "суп с фрикадельками")) {
            val r = runCatching { f.find(q) }.getOrElse { println("LIVE [$q] ERR ${it.message}"); null }
            println("LIVE [$q] → " + (r?.let { "${it.name} | ${it.grams} г | ${it.per100.kcal} ккал/100 Б${it.per100.p} Ж${it.per100.f} У${it.per100.c} | ${it.source}" } ?: "—"))
        }
        val p = FoodPipeline(MemoryStore(), kind = FoodKind.HOME)
        val res = p.run("пирог с грибами, блины, сырники со сметаной, тарелка борща, 2 котлеты")
        println("LIVE pipeline: ${res.how} | " + res.items.joinToString { it.name + " " + it.grams } + " | missing " + res.missing)
    }
}
