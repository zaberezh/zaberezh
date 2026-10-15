package by.zaberezh.forma.core.gym

import by.zaberezh.forma.core.store.newId

/** Что пользователь вводит в форме упражнения. */
data class ExerciseInput(
    val name: String,
    val weight: Double?,        // рабочий вес сейчас (для упражнений с весом тела — доп. вес)
    val reps: String,           // «10» или «8-12»
    val sets: Int,
    val muscles: Set<String>?,  // основные мышцы, можно несколько (null — угадать по названию)
    val bodyweight: Boolean,
    val step: Double?,          // шаг прибавки (null — угадать)
)

/** «10» → 10–14, «6» → 6–9, «8-12» / «8–12» → 8–12. */
fun parseReps(text: String): Pair<Int, Int>? {
    val nums = Regex("\\d+").findAll(text).map { it.value.toInt() }.filter { it in 1..100 }.toList()
    return when {
        nums.size >= 2 -> minOf(nums[0], nums[1]) to maxOf(nums[0], nums[1])
        nums.size == 1 -> nums[0] to nums[0] + if (nums[0] <= 6) 3 else 4
        else -> null
    }
}

private fun has(n: String, vararg w: String) = w.any { it in n }

/**
 * Мышцы по названию: основные = 1 (у базовых упражнений их бывает две — становая: бицепс бедра и ягодицы),
 * вспомогательные = 0.5 и меньше. Пусто — не угадали.
 * Порядок проверок важен: сначала то, что легко спутать по одному слову, — «сгибания/разгибания запястий»
 * (предплечья, а не бицепс/трицепс), «французский жим» и «жим узким хватом» (трицепс, а не грудь),
 * «махи перед собой» (передняя дельта), «тяга к подбородку» (средняя дельта, а не спина).
 */
fun guessMuscles(name: String): Map<String, Double> {
    val n = name.lowercase().replace('ё', 'е')
    fun h(vararg w: String) = has(n, *w)
    return when {
        // ноги
        h("фронтальн") && h("присед") -> mapOf("quads" to 1.0, "glutes" to 0.5, "abs" to 0.25)
        h("выпад", "болгар", "зашагив", "сплит") -> mapOf("quads" to 1.0, "glutes" to 1.0)
        h("жим ног", "присед", "гакк", "хакк") -> mapOf("quads" to 1.0, "glutes" to 0.5)
        h("разгибание ног", "разгибания ног") -> mapOf("quads" to 1.0)
        h("сгибание ног", "сгибания ног", "бицепс бедр") -> mapOf("hams" to 1.0)
        h("сумо") -> mapOf("hams" to 1.0, "glutes" to 1.0, "quads" to 0.5, "back" to 0.5)
        h("румын", "мертв", "становая") -> mapOf("hams" to 1.0, "glutes" to 1.0, "back" to 0.5)
        h("гуд морнинг", "гиперэкстенз", "разгибание спины", "разгибания спины") -> mapOf("hams" to 1.0, "glutes" to 1.0)
        h("ягодичн", "мостик", "мост", "хип", "траст", "отведение ног", "отведения ног") -> mapOf("glutes" to 1.0)
        h("икр", "носк") -> mapOf("calves" to 1.0)
        // предплечья — раньше бицепса и трицепса: «сгибания запястий», «разгибания запястий»
        h("запяст", "предплеч", "кистев", "вис на") || n.trim().startsWith("вис") -> mapOf("forearms" to 1.0)
        // дельты
        h("мах", "развед") && h("наклон", "задн") -> mapOf("rear_delts" to 1.0)
        h("к лицу", "face", "задн") || h("обратн") && h("развед", "бабочк") -> mapOf("rear_delts" to 1.0)
        h("перед собой") -> mapOf("front_delts" to 1.0)
        h("подбородк") -> mapOf("side_delts" to 1.0, "back" to 0.5)          // тяга к подбородку: средняя дельта и трапеция
        h("мах", "в стороны", "разведение рук") -> mapOf("side_delts" to 1.0)
        // трицепс — раньше жимов: «французский жим», «жим узким хватом», «обратные отжимания от скамьи»
        h("француз", "кикбек", "трицепс") -> mapOf("triceps" to 1.0)
        h("узк") && h("жим") -> mapOf("triceps" to 1.0, "chest" to 0.5, "front_delts" to 0.5)
        h("отжим") && h("обратн", "скамь") -> mapOf("triceps" to 1.0, "chest" to 0.5)
        h("разгибан") -> mapOf("triceps" to 1.0)
        h("брусь") -> mapOf("chest" to 1.0, "triceps" to 1.0, "front_delts" to 0.5)
        // жим над головой: стоя, армейский, Арнольда, «на плечи»; сидя — со штангой или гантелями (сидя в тренажёре — от груди)
        h("жим") && !h("лежа", "наклон", "°", "градус") && (h("армейск", "над голов", "плеч", "арнольд", "вверх", "за голов", "стоя") ||
            h("сидя") && h("гантел", "штанг") && !h("от груди")) -> mapOf("front_delts" to 1.0, "side_delts" to 0.5, "triceps" to 0.5)
        h("жим", "отжим") -> mapOf("chest" to 1.0, "triceps" to 0.5, "front_delts" to 0.5)
        // пуловер на блоке и тяга прямыми руками — широчайшие; лёжа с гантелью — грудь и широчайшие
        h("пуловер", "прямыми руками") ->
            if (h("блок", "кроссовер", "канат", "трос", "стоя", "прямыми руками")) mapOf("back" to 1.0) else mapOf("chest" to 1.0, "back" to 0.5)
        // спина
        h("подтяг") && h("обратн", "узк", "к груди") -> mapOf("back" to 1.0, "biceps" to 1.0)
        h("подтяг", "верхн", "вертикальн", "пулдаун") -> mapOf("back" to 1.0, "biceps" to 0.5)
        h("тяга") -> mapOf("back" to 1.0, "rear_delts" to 0.5, "biceps" to 0.5)
        h("фермер") -> mapOf("forearms" to 1.0, "back" to 0.5, "abs" to 0.5)
        h("шраг") -> mapOf("back" to 1.0, "forearms" to 0.5)
        h("обратн") && h("хват") -> mapOf("forearms" to 1.0, "biceps" to 0.5)
        // руки и пресс — раньше сведений: «сгибания рук в кроссовере», «скручивания на блоке» — не грудь
        h("молот") -> mapOf("biceps" to 1.0, "forearms" to 0.5)
        h("бицепс", "сгибан", "подъем штанги", "подъем гантел", "скотт") -> mapOf("biceps" to 1.0)
        h("пресс", "скручив", "подъем ног", "планк", "ролик") -> mapOf("abs" to 1.0)
        h("сведен", "бабочк", "кроссовер", "разводк", "развед", "пек", "fly") -> mapOf("chest" to 1.0)
        else -> emptyMap()
    }
}

fun guessBodyweight(name: String): Double {
    val n = name.lowercase()
    return when { has(n, "подтяг", "брусь") -> 1.0; has(n, "отжим") -> 0.65; else -> 0.0 }
}

/**
 * Шаг прибавки — какой реально бывает в зале: гантели идут через 2 кг (лёгкие для махов — через 1), блоки и тренажёры —
 * через 5 (изоляция на блоке — 2,5), штанга — 2,5. Упражнения без веса (планка, вис, подъём ног) — 0: растут повторы.
 */
fun guessStep(name: String): Double {
    val n = name.lowercase().replace('ё', 'е')
    val machine = has(n, "блок", "кроссовер", "канат", "трос", "тренажер")
    val iso = has(n, "мах", "развед", "сведен", "бабочк", "бицепс", "сгибан", "разгибан", "трицепс", "француз", "молот",
        "к лицу", "кикбек", "скручив")
    return when {
        has(n, "планк", "вис", "подъем ног") -> 0.0
        has(n, "мах", "развед") && !machine -> 1.0
        has(n, "запяст", "предплеч") -> 1.0
        has(n, "гантел", "молот") -> 2.0
        has(n, "жим ног", "гакк", "хакк") -> 5.0
        machine -> if (iso) 2.5 else 5.0
        else -> 2.5
    }
}

/** Добавить (id == null) или обновить упражнение в базе. */
fun Program.upsert(id: String?, i: ExerciseInput): Program {
    require(i.name.isNotBlank()) { "Введи название" }
    val (lo, hi) = parseReps(i.reps) ?: throw IllegalArgumentException("Повторы: число или диапазон, например 10 или 8-12")
    require(i.sets in 1..10) { "Подходов: от 1 до 10" }
    val guessed = guessMuscles(i.name)
    val muscles = when {
        i.muscles == null -> guessed.ifEmpty { throw IllegalArgumentException("Выбери основную мышцу") }
        i.muscles.isEmpty() -> throw IllegalArgumentException("Выбери хотя бы одну мышцу")
        // выбранные — основные (1), угаданные вспомогательные (½) не теряются, если их не выбрали явно
        else -> i.muscles.associateWith { 1.0 } + guessed.filter { (m, k) -> k < 1.0 && m !in i.muscles }
    }
    val old = id?.let(::ex)
    val exId = old?.id ?: ("u" + newId().take(8))
    val bw = if (i.bodyweight) guessBodyweight(i.name).takeIf { it > 0 } ?: 1.0 else 0.0
    val compound = muscles.size > 1
    val e = (old ?: Exercise(exId, i.name.trim(), muscles)).copy(
        name = i.name.trim(), muscles = muscles, sets = i.sets, repMin = lo, repMax = hi,
        step = i.step ?: guessStep(i.name), bw = bw, startWeight = i.weight,
        rir = old?.rir ?: if (compound) 2 else 1, restSec = old?.restSec ?: if (compound) 150 else 90,
    )
    val exercises = if (old == null) exercises + e else exercises.map { if (it.id == exId) e else it }
    return copy(exercises = exercises)
}

fun Program.remove(id: String): Program =
    copy(exercises = exercises.filter { it.id != id }, days = days.map { it.copy(exercises = it.exercises - id) })

/** Упражнения на предплечья, которые добавляются в базу, если там ничего на предплечья нет. */
val FOREARM_SEED = listOf(
    Exercise("fa_wrist_curl", "Сгибания запястий с гантелями (ладони вверх)", mapOf("forearms" to 1.0), sets = 3, repMin = 12, repMax = 20, step = 1.0, rir = 1, restSec = 90,
        note = "Предплечья на скамье, кисть свисает; опускай гантель до пальцев и поднимай запястьем, без рывков."),
    Exercise("fa_wrist_ext", "Разгибания запястий с гантелями (ладони вниз)", mapOf("forearms" to 1.0), sets = 3, repMin = 12, repMax = 20, step = 1.0, rir = 1, restSec = 90,
        note = "Та же позиция, ладони вниз; вес меньше, чем в сгибаниях."),
    Exercise("fa_reverse_curl", "Подъём штанги обратным хватом", mapOf("forearms" to 1.0, "biceps" to 0.5), sets = 3, repMin = 8, repMax = 12, step = 2.5, rir = 1, restSec = 90,
        note = "Хват сверху на ширине плеч, локти прижаты — нагружает плечелучевую и разгибатели."),
)
