package by.zaberezh.forma.core

import by.zaberezh.forma.core.body.BodyModule
import by.zaberezh.forma.core.food.FoodModule
import by.zaberezh.forma.core.daily.CounterDef
import by.zaberezh.forma.core.daily.CounterModule
import by.zaberezh.forma.core.daily.TestDef
import by.zaberezh.forma.core.daily.TestModule
import by.zaberezh.forma.core.gym.GymModule
import by.zaberezh.forma.core.gym.PROGRAM
import by.zaberezh.forma.core.sleep.SleepModule
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
    val activity: Double = 1.375,      // множитель к BMR: только 3 силовые в неделю (ходьба не учитывается)
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
    val gymWeekends: Boolean = true,    // выходные тоже могут быть днями зала
    val minVisitMin: Int = 75,
    val morningHour: Int = 8,
    val morningMinute: Int = 0,
    val checkupDays: Int = 14,
    val apiKey: String = "",
    val model: String = "claude-opus-5-5",
    val apiUrl: String = "",            // пусто = api.anthropic.com; иначе адрес Anthropic-совместимого посредника
    val kcalOverride: Int? = null,
    val schema: Int = 0,
    val sleepTargetH: Double = 8.0,
    val wakeHour: Int = 7,
    val wakeMinute: Int = 30,
    val bedReminder: Boolean = true,
    val counters: List<CounterDef> = listOf(CounterDef("pullups", "Подтягивания")),
    val tests: List<TestDef> = listOf(TestDef("pullups_max", "Подтягивания: максимум за подход", 7, "раз",
        "Раз в неделю, в день отдыха или в начале тренировки после разминки. Полная амплитуда: из виса на прямых руках до подбородка над перекладиной, без раскачки. Один подход до отказа.")),
)

val SETTINGS = Pref("settings", Settings.serializer()) { Settings() }

/** Миграция сохранённых настроек при обновлении приложения. */
fun migrateSettings(store: Store) {
    var st = SETTINGS.get(store)
    if (st.schema < 2) {
        // v2: активность без ходьбы — старые значения по умолчанию (1.55/1.45) → 1.375
        val p = st.profile
        val act = if (p.activity == 1.55 || p.activity == 1.45) 1.375 else p.activity
        st = st.copy(profile = p.copy(activity = act), schema = 2)
    }
    if (st.schema < 3) {
        // v3: черновая программа убрана — если сохранена она, сбрасываем на пустую
        if (store.kvGet(PROGRAM.key)?.contains("черновик") == true) store.kvPut(PROGRAM.key, null)
        st = st.copy(schema = 3)
    }
    SETTINGS.set(store, st)
}

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
    val all: List<Module> = listOf(GymModule, CounterModule, TestModule, SleepModule, BodyModule, FoodModule)
}
