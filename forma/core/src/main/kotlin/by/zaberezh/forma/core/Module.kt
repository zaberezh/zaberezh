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
data class Settings(
    val profile: Profile = Profile(),
    val sessionsPerWeek: Int = 3,
    val gymWeekends: Boolean = true,    // выходные тоже могут быть днями зала
    val logEachSet: Boolean = true,
    val advancedMacros: Boolean = false, // продвинутое КБЖУ: ещё и клетчатка (цель 30 г)     // записывать каждый подход; false — один итог «вес × повторы × подходы» на упражнение
    val supersets: Boolean = false,     // суперсеты не используются (решение пользователя)
    val split: String = "ul",
    val sessionMin: Int = 90,
    val wakeAlarm: Boolean = true,      // беззвучный будильник по первой паре
    val iisWatch: Boolean = true,       // фоновая проверка ИИС: отметки, пропуски, лабы
    val waterReminder: Boolean = true,  // «попей воды» каждый час
    val waterWeekdayFrom: Int = 16, val waterWeekendFrom: Int = 11, val waterTo: Int = 23,
    val morningHour: Int = 8,
    val morningMinute: Int = 0,
    val weighReminder: Boolean = true,  // напоминание взвеситься
    val weighHour: Int = 7, val weighMinute: Int = 20,               // будни
    val weighWeekendHour: Int = 11, val weighWeekendMinute: Int = 0, // выходные
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
    val tests: List<TestDef> = listOf(PULLUP_MAX),
)

/** Максимум подтягиваний за подход — можно записывать каждый день (одна запись на день, правится). */
val PULLUP_MAX = TestDef("pullups_max", "Подтягивания: максимум за подход", 1, "раз",
    "Полная амплитуда: из виса на прямых руках до подбородка над перекладиной, без раскачки. Один подход до отказа, после разминки. " +
        "Повторный ввод за день исправляет запись.")

val SETTINGS = Pref("settings", Settings.serializer()) { Settings() }

/** Последняя версия схемы настроек (см. миграции ниже). */
const val SETTINGS_SCHEMA = 6

/**
 * Миграция сохранённых настроек при обновлении приложения.
 * Новая установка (база пустая) миграции не проходит: в ней ничего не создаётся — ни упражнений, ни записей.
 */
fun migrateSettings(store: Store) {
    val fresh = store.kvGet(SETTINGS.key) == null && store.kvGet(PROGRAM.key) == null && store.allTypes().isEmpty()
    if (fresh) { SETTINGS.set(store, Settings(schema = SETTINGS_SCHEMA)); return }
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
    if (st.schema < 4) st = st.copy(supersets = false, schema = 4) // v4: суперсеты выключены
    if (st.schema < 5) { // v5: акцент на предплечья — добавить упражнения, если в базе на них ничего нет
        val p = PROGRAM.get(store)
        if (p.exercises.none { (it.muscles["forearms"] ?: 0.0) >= 1.0 })
            PROGRAM.set(store, p.copy(exercises = p.exercises + by.zaberezh.forma.core.gym.FOREARM_SEED.filter { s -> p.ex(s.id) == null }))
        st = st.copy(schema = 5)
    }
    if (st.schema < 6) { // v6: максимум подтягиваний — каждый день, а не раз в неделю
        st = st.copy(tests = st.tests.map { if (it.id == PULLUP_MAX.id) PULLUP_MAX else it }, schema = 6)
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
