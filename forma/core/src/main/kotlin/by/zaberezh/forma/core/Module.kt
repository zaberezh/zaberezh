package by.zaberezh.forma.core

import by.zaberezh.forma.core.body.BodyModule
import by.zaberezh.forma.core.food.FoodModule
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.store.Pref
import by.zaberezh.forma.core.store.Store
import by.zaberezh.forma.core.store.today
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class Profile(
    val age: Int = 18,
    val heightCm: Double = 180.0,
    val male: Boolean = true,
    val activity: Double = 1.45,       // множитель к BMR: 3 силовые без кардио + учёба/ходьба
    val gainKgPerWeek: Double = 0.1,   // целевой темп веса: медленный набор/рекомпозиция
    val proteinPerKg: Double = 2.0,
    val fatShare: Double = 0.25,       // доля калорий из жира
)

@Serializable
data class GymPlace(val id: String, val name: String, val lat: Double, val lon: Double, val radiusM: Float = 150f)

@Serializable
data class Settings(
    val profile: Profile = Profile(),
    val gyms: List<GymPlace> = listOf(
        GymPlace("adrenalin", "Адреналин Восток (Мстиславца 9)", 53.932930, 27.649587),
        GymPlace("dvs", "Дворец водного спорта (Сурганова 2а)", 53.918795, 27.607268),
    ),
    val sessionsPerWeek: Int = 3,
    val minVisitMin: Int = 75,
    val morningHour: Int = 8,
    val morningMinute: Int = 0,
    val checkupDays: Int = 14,
    val apiKey: String = "",
    val model: String = "claude-opus-5-5",
    val kcalOverride: Int? = null,
)

val SETTINGS = Pref("settings", Settings.serializer()) { Settings() }

/** Контекст вычислений: хранилище + «сегодня». */
class Ctx(val store: Store, val today: LocalDate = today()) {
    val settings: Settings by lazy { SETTINGS.get(store) }
}

/** Раздел отчёта/чекапа. `facts` — сжатые данные для анализа (в т.ч. ИИ). */
data class Section(val title: String, val lines: List<String>, val actions: List<String> = emptyList(), val facts: Map<String, Any?> = emptyMap())

/**
 * Модуль-сфера жизни. Чтобы добавить новую сферу (готовка, сон, учёба…),
 * реализуй этот интерфейс и добавь в [Modules.all] — утреннее уведомление,
 * чекап и экспорт подхватят её автоматически.
 */
interface Module {
    val id: String
    val title: String
    fun morning(ctx: Ctx): List<String> = emptyList()
    fun checkup(ctx: Ctx, from: LocalDate, to: LocalDate): Section? = null
}

object Modules {
    val all: List<Module> = listOf(GymModule, BodyModule, FoodModule)
}
