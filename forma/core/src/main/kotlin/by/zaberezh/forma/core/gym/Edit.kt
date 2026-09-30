package by.zaberezh.forma.core.gym

import by.zaberezh.forma.core.store.newId

/** Что пользователь вводит в форме упражнения. */
data class ExerciseInput(
    val name: String,
    val weight: Double?,        // рабочий вес сейчас (для упражнений с весом тела — доп. вес)
    val reps: String,           // «10» или «8-12»
    val sets: Int,
    val muscle: String?,        // главная мышца (null — угадать по названию)
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

/** Мышцы по названию: главная = 1, вспомогательные = 0.5. Пусто — не угадали. */
fun guessMuscles(name: String): Map<String, Double> {
    val n = name.lowercase().replace('ё', 'е')
    return when {
        has(n, "жим ног", "присед", "гакк", "хакк") -> mapOf("quads" to 1.0, "glutes" to 0.5)
        has(n, "выпад", "болгар", "зашагив") -> mapOf("quads" to 1.0, "glutes" to 1.0)
        has(n, "разгибание ног", "разгибания ног") -> mapOf("quads" to 1.0)
        has(n, "сгибание ног", "сгибания ног", "бицепс бедр") -> mapOf("hams" to 1.0)
        has(n, "румын", "мертв", "становая") -> mapOf("hams" to 1.0, "glutes" to 0.5, "back" to 0.25)
        has(n, "ягодичн", "мостик", "отведение ног") -> mapOf("glutes" to 1.0)
        has(n, "икр", "носк") -> mapOf("calves" to 1.0)
        has(n, "мах", "развед") && has(n, "наклон", "задн") -> mapOf("rear_delts" to 1.0)
        has(n, "к лицу", "face", "задн") || has(n, "обратн") && has(n, "развед", "бабочк") -> mapOf("rear_delts" to 1.0)
        has(n, "мах", "в стороны", "разведение рук") -> mapOf("side_delts" to 1.0)
        has(n, "жим") && has(n, "стоя", "сидя", "над голов", "армейск", "плеч", "шраг") && !has(n, "лежа") ->
            mapOf("front_delts" to 1.0, "side_delts" to 0.5, "triceps" to 0.5)
        has(n, "брусь") -> mapOf("chest" to 1.0, "triceps" to 1.0, "front_delts" to 0.5)
        has(n, "узк") && has(n, "жим") -> mapOf("triceps" to 1.0, "chest" to 0.5)
        has(n, "жим", "отжим") -> mapOf("chest" to 1.0, "triceps" to 0.5, "front_delts" to 0.5)
        has(n, "сведен", "бабочк", "кроссовер", "пуловер", "разводк") -> mapOf("chest" to 1.0)
        has(n, "подтяг", "верхн", "вертикальн", "пулдаун") -> mapOf("back" to 1.0, "biceps" to 0.5)
        has(n, "тяга") -> mapOf("back" to 1.0, "rear_delts" to 0.5, "biceps" to 0.5)
        has(n, "трицепс", "разгибан", "француз") -> mapOf("triceps" to 1.0)
        has(n, "предплеч", "запяст", "вис на", "фермер", "кистев") -> mapOf("forearms" to 1.0)
        has(n, "обратн") && has(n, "хват") -> mapOf("forearms" to 1.0, "biceps" to 0.5)
        has(n, "молот") -> mapOf("biceps" to 1.0, "forearms" to 0.5)
        has(n, "бицепс", "сгибан", "подъем штанги", "подъем гантел", "скотт") -> mapOf("biceps" to 1.0)
        has(n, "пресс", "скручив", "подъем ног", "планк", "ролик") -> mapOf("abs" to 1.0)
        else -> emptyMap()
    }
}

fun guessBodyweight(name: String): Double {
    val n = name.lowercase()
    return when { has(n, "подтяг", "брусь") -> 1.0; has(n, "отжим") -> 0.65; else -> 0.0 }
}

fun guessStep(name: String): Double {
    val n = name.lowercase().replace('ё', 'е')
    return when {
        has(n, "мах", "развед", "сведен", "бицепс", "молот", "трицепс", "француз", "предплеч") -> 1.0
        has(n, "гантел") -> 2.0
        has(n, "жим ног", "тренажер", "блок", "кроссовер", "гакк") -> 5.0
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
        i.muscle == null -> guessed.ifEmpty { throw IllegalArgumentException("Выбери основную мышцу") }
        guessed[i.muscle] == 1.0 -> guessed
        else -> mapOf(i.muscle to 1.0)
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
