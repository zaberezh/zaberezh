package by.zaberezh.forma.core

import by.zaberezh.forma.core.food.LibraryResolver
import by.zaberezh.forma.core.gym.PROGRAM
import by.zaberezh.forma.core.store.MemoryStore
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/** Свой протеиновый коктейль — в библиотеке после обновления, целиком одной позицией. */
class ShakeTest {
    @Test fun shakeFromLibrary() {
        val s = MemoryStore()
        PROGRAM.set(s, PROGRAM.get(s)); SETTINGS.set(s, Settings(schema = 7))   // приложение уже стоит — не новая установка
        migrateSettings(s)
        val one = LibraryResolver(s).resolve("протеиновый коктейль")!!.single()
        assertEquals(359, one.total.kcal.roundToInt()); assertEquals(33, one.total.p.roundToInt())
        assertEquals(2, LibraryResolver(s).resolve("2 шейк")!!.single().let { (it.grams / 408).roundToInt() })
        migrateSettings(s)   // второй раз не дублирует и не трогает
        assertEquals(1, by.zaberezh.forma.core.food.FoodModule.library(s).count { it.name == "Протеиновый коктейль" })
    }
}

class ShakeMenuTest {
    @Test fun notTakenByCafeMenus() {
        listOf("протеиновый коктейль", "шейк", "коктейль").forEach {
            kotlin.test.assertTrue(by.zaberezh.forma.core.food.Menus.resolve(it).items.isEmpty(), it)
        }
    }
}
