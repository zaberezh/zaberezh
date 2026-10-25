package by.zaberezh.forma.core.gym

/**
 * Исправить в базе то, что было угадано по названию старыми правилами: «Французский жим» считался грудью,
 * «Разгибания запястий» — трицепсом, «Тяга к подбородку» — спиной, у молотков и блоков был шаг 1 кг.
 * Трогается только угаданное автоматически (совпадает со старой догадкой); выбранное руками остаётся как есть.
 */
fun Program.reguessed(): Program = copy(exercises = exercises.map { e ->
    var x = e
    val old = legacyMuscles(e.name)
    val now = guessMuscles(e.name)
    if (old.isNotEmpty() && now.isNotEmpty() && e.muscles == old && now != old) {
        val wasCompound = old.size > 1; val compound = now.size > 1
        x = x.copy(
            muscles = now,
            rir = if (e.rir == (if (wasCompound) 2 else 1)) (if (compound) 2 else 1) else e.rir,
            restSec = if (e.restSec == (if (wasCompound) 150 else 90)) (if (compound) 150 else 90) else e.restSec,
        )
    }
    if (e.step == legacyStep(e.name) && guessStep(e.name) != e.step) x = x.copy(step = guessStep(e.name))
    x
})

private fun has(n: String, vararg w: String) = w.any { it in n }

/** Угадывание мышц до исправления (Grind ≤ 122) — только чтобы узнать, что в базе угадано автоматически. */
private fun legacyMuscles(name: String): Map<String, Double> {
    val n = name.lowercase().replace('ё', 'е')
    return when {
        has(n, "жим ног", "присед", "гакк", "хакк") -> mapOf("quads" to 1.0, "glutes" to 0.5)
        has(n, "выпад", "болгар", "зашагив") -> mapOf("quads" to 1.0, "glutes" to 1.0)
        has(n, "фронтальн") && has(n, "присед") -> mapOf("quads" to 1.0, "glutes" to 0.5, "abs" to 0.25)
        has(n, "разгибание ног", "разгибания ног") -> mapOf("quads" to 1.0)
        has(n, "сгибание ног", "сгибания ног", "бицепс бедр") -> mapOf("hams" to 1.0)
        has(n, "румын", "мертв", "становая") -> mapOf("hams" to 1.0, "glutes" to 1.0, "back" to 0.5)
        has(n, "гуд морнинг", "гиперэкстенз") -> mapOf("hams" to 1.0, "glutes" to 1.0)
        has(n, "ягодичн", "мостик", "отведение ног") -> mapOf("glutes" to 1.0)
        has(n, "икр", "носк") -> mapOf("calves" to 1.0)
        has(n, "мах", "развед") && has(n, "наклон", "задн") -> mapOf("rear_delts" to 1.0)
        has(n, "к лицу", "face", "задн") || has(n, "обратн") && has(n, "развед", "бабочк") -> mapOf("rear_delts" to 1.0)
        has(n, "мах", "в стороны", "разведение рук") -> mapOf("side_delts" to 1.0)
        has(n, "жим") && has(n, "стоя", "сидя", "над голов", "армейск", "плеч", "шраг") && !has(n, "лежа") ->
            mapOf("front_delts" to 1.0, "side_delts" to 0.5, "triceps" to 0.5)
        has(n, "брусь") -> mapOf("chest" to 1.0, "triceps" to 1.0, "front_delts" to 0.5)
        has(n, "узк") && has(n, "жим") -> mapOf("triceps" to 1.0, "chest" to 1.0, "front_delts" to 0.5)
        has(n, "жим", "отжим") -> mapOf("chest" to 1.0, "triceps" to 0.5, "front_delts" to 0.5)
        has(n, "сведен", "бабочк", "кроссовер", "пуловер", "разводк") -> mapOf("chest" to 1.0)
        has(n, "подтяг") && has(n, "обратн", "узк", "к груди") -> mapOf("back" to 1.0, "biceps" to 1.0)
        has(n, "подтяг", "верхн", "вертикальн", "пулдаун") -> mapOf("back" to 1.0, "biceps" to 0.5)
        has(n, "тяга") -> mapOf("back" to 1.0, "rear_delts" to 0.5, "biceps" to 0.5)
        has(n, "трицепс", "разгибан", "француз") -> mapOf("triceps" to 1.0)
        has(n, "фермер") -> mapOf("forearms" to 1.0, "back" to 0.5, "abs" to 0.5)
        has(n, "шраг") -> mapOf("back" to 1.0, "forearms" to 0.5)
        has(n, "предплеч", "запяст", "вис на", "кистев") -> mapOf("forearms" to 1.0)
        has(n, "обратн") && has(n, "хват") -> mapOf("forearms" to 1.0, "biceps" to 0.5)
        has(n, "молот") -> mapOf("biceps" to 1.0, "forearms" to 0.5)
        has(n, "бицепс", "сгибан", "подъем штанги", "подъем гантел", "скотт") -> mapOf("biceps" to 1.0)
        has(n, "пресс", "скручив", "подъем ног", "планк", "ролик") -> mapOf("abs" to 1.0)
        else -> emptyMap()
    }
}

private fun legacyStep(name: String): Double {
    val n = name.lowercase().replace('ё', 'е')
    return when {
        has(n, "мах", "развед", "сведен", "бицепс", "молот", "трицепс", "француз", "предплеч") -> 1.0
        has(n, "гантел") -> 2.0
        has(n, "жим ног", "тренажер", "блок", "кроссовер", "гакк") -> 5.0
        else -> 2.5
    }
}

